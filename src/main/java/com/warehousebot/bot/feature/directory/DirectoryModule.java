package com.warehousebot.bot.feature.directory;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.MessageContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.domain.DirectoryContact;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.repository.DirectoryRepository;
import com.warehousebot.util.Formats;
import org.telegram.telegrambots.meta.api.objects.Contact;

import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** «📞 Справочник»: телефоны подразделений и контакты, добавленные сотрудниками. */
public final class DirectoryModule implements BotModule {

    /** Категория, в которую сотрудник добавляет контакт (ждём текст или карточку контакта). */
    private static final SessionKey<Integer> WAITING_CATEGORY = SessionKey.dialog("directory.waitingCategory");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final DirectoryRepository contacts;

    public DirectoryModule(Messenger messenger, Ui ui, MainMenu menu, DirectoryRepository contacts) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.contacts = contacts;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.DIRECTORY, ctx -> showDirectory(ctx.chatId(), ctx.role()));
        r.onCallback("DIR_ADD_MENU").handle(ctx -> messenger.send(ctx.chatId(),
                "➕ <b>В какой раздел добавить контакт?</b>\nВыберите категорию ниже:", DirectoryView.categoriesKeyboard()));
        r.onCallbackPrefix("DIR_CAT:").handle(this::chooseCategory);
        r.onCallback("DIR_DEL_MENU").adminOnly().handle(ctx -> showDeleteMenu(ctx.chatId()));
        r.onCallbackPrefix("DIR_DEL:").adminOnly().handle(this::delete);
        r.onCallback("DIR_BACK").handle(ctx -> showDirectory(ctx.chatId(), ctx.role()));

        r.onText(TextStage.DIRECTORY_CONTACT_INPUT, this::onContactText);
        r.onContact(this::onSharedContact);
    }

    private void showDirectory(long chatId, Role role) {
        Keyboards.Rows rows = Keyboards.rows().add(btn("➕ Добавить контакт", "DIR_ADD_MENU"));
        if (role.isAdmin()) rows.add(btn("🗑 Удалить добавленный", "DIR_DEL_MENU"));
        rows.add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE));
        messenger.send(chatId, DirectoryView.directoryText(contacts.findAll()), rows.build());
    }

    private void chooseCategory(CallbackContext ctx) {
        ctx.session().put(WAITING_CATEGORY, ctx.intPart(1));
        ui.cancelPrompt(ctx.chatId(), "✍ <b>Отлично!</b>\n\nВы можете <b>поделиться контактом</b> из телефонной книги (📎 Скрепка ➔ Контакт)\n\n👇 ИЛИ напишите данные вручную одним сообщением:\n<i>Пример: Электрик Иванов Иван: +375(29)111-22-33</i>");
    }

    private void showDeleteMenu(long chatId) {
        List<DirectoryContact> list = contacts.findAll();
        if (list.isEmpty()) {
            menu.show(chatId, Role.ADMIN, "ℹ️ В справочник еще не добавлено ни одного стороннего контакта.");
            return;
        }
        Keyboards.Rows rows = Keyboards.rows();
        for (DirectoryContact c : list) rows.add(btn("🗑 " + Formats.cut(c.text(), 30), "DIR_DEL:" + c.id()));
        rows.add(btn("🔙 Назад", "DIR_BACK"));
        messenger.send(chatId, "🗑 <b>Удаление контакта</b>\nНажмите на контакт, который хотите удалить:", rows.build());
    }

    private void delete(CallbackContext ctx) {
        contacts.delete(ctx.intPart(1));
        showDeleteMenu(ctx.chatId());
    }

    private boolean onContactText(TextContext ctx) {
        Integer category = ctx.session().remove(WAITING_CATEGORY);
        if (category == null) return false;
        save(ctx.chatId(), ctx.role(), category, ctx.trimmed(),
                "✅ <b>Контакт успешно добавлен!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
        return true;
    }

    private boolean onSharedContact(MessageContext ctx) {
        Integer category = ctx.session().remove(WAITING_CATEGORY);
        if (category == null) return false;
        Contact contact = ctx.message().getContact();
        String name = contact.getFirstName();
        if (contact.getLastName() != null && !contact.getLastName().isEmpty()) name += " " + contact.getLastName();
        String phone = contact.getPhoneNumber();
        if (!phone.startsWith("+")) phone = "+" + phone;
        save(ctx.chatId(), ctx.role(), category, name + ": " + phone,
                "✅ <b>Контакт успешно добавлен из телефонной книги!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
        return true;
    }

    private void save(long chatId, Role role, int category, String text, String successMessage) {
        try {
            contacts.add(category, text, chatId);
        } catch (DataAccessException e) {
            menu.show(chatId, role, "❌ Ошибка при сохранении контакта.");
            return;
        }
        menu.show(chatId, role, successMessage);
        showDirectory(chatId, role);
    }
}
