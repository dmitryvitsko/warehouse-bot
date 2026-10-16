package com.warehousebot.bot.feature.orsh;

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
import com.warehousebot.domain.OrshCabinet;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.service.OrshService;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;

import java.io.File;
import java.util.List;
import java.util.Optional;

/** «📸 Плановый осмотр ОРШ»: план осмотра, фото на почту руководству, причины «невозможно осмотреть», статистика. */
public final class OrshModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(OrshModule.class);

    /** Маркер в WAITING_PROBLEM_REASON: администратор вводит новый шкаф ОРШ вручную. */
    private static final int ADD_MANUAL_ORSH_SENTINEL = -1;

    /** ОРШ, для которого ждём фото. */
    private static final SessionKey<Integer> WAITING_PHOTO = SessionKey.dialog("orsh.waitingPhoto");
    /** ОРШ, для которого ждём причину невозможности осмотра (или -1 для добавления нового ОРШ). */
    private static final SessionKey<Integer> WAITING_PROBLEM_REASON = SessionKey.dialog("orsh.waitingProblemReason");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final OrshService orsh;
    private final EmailSender email;

    public OrshModule(Messenger messenger, Ui ui, MainMenu menu, OrshService orsh, EmailSender email) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.orsh = orsh;
        this.email = email;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.ORSH_INSPECTION, ctx -> showPending(ctx.chatId(), ctx.role()));
        r.onCommand(MainMenu.ADMIN_ORSH_STATS, ctx -> {
            if (ctx.isAdmin()) ui.closable(ctx.chatId(), OrshView.statisticsText(orsh.statistics()));
        });
        r.onCallback("ORSH_ADD_MANUAL").adminOnly().handle(this::askAddManual);
        r.onCallbackPrefix("ORSH_SEL:").handle(this::showDetails);
        r.onCallbackPrefix("ORSH_PROB:").handle(this::reportProblem);
        r.onText(TextStage.ORSH_PROBLEM_INPUT, this::onProblemReason);
        r.onPhoto(this::onPhoto);
    }

    private void showPending(long chatId, Role role) {
        List<OrshCabinet> pending = orsh.pending();
        if (pending.isEmpty()) {
            if (role.isAdmin()) {
                messenger.send(chatId, "🎉 <b>План осмотра пуст!</b>\nВы можете загрузить план из Excel или добавить шкаф вручную:",
                        OrshView.pendingListKeyboard(List.of(), true));
            } else {
                menu.show(chatId, role, "🎉 <b>План осмотра пуст!</b>");
            }
            return;
        }
        messenger.send(chatId, OrshView.pendingListText(pending.size()),
                OrshView.pendingListKeyboard(pending, role.isAdmin()));
    }

    private void askAddManual(CallbackContext ctx) {
        ctx.session().remove(WAITING_PHOTO);
        ctx.session().put(WAITING_PROBLEM_REASON, ADD_MANUAL_ORSH_SENTINEL);
        messenger.edit(ctx.chatId(), ctx.messageId(), OrshView.ADD_MANUAL_PROMPT, Keyboards.cancelPrompt());
    }

    private void showDetails(CallbackContext ctx) {
        long chatId = ctx.chatId();
        int orshId = ctx.intPart(1);
        Optional<OrshCabinet> cabinet = orsh.find(orshId);
        if (cabinet.isEmpty()) {
            menu.show(chatId, ctx.role(), "❌ Шкаф не найден.");
            return;
        }
        ctx.session().put(WAITING_PHOTO, orshId);
        ui.cancelPrompt(chatId, "Подготовка к осмотру...");
        messenger.send(chatId, OrshView.detailsText(cabinet.get()), OrshView.detailsKeyboard(orshId));
    }

    private void reportProblem(CallbackContext ctx) {
        int orshId = ctx.intPart(1);
        ctx.session().remove(WAITING_PHOTO);
        ctx.session().put(WAITING_PROBLEM_REASON, orshId);
        ui.cancelPrompt(ctx.chatId(), "⚠️ <b>Невозможно сделать фото!</b>\nНапишите причину (например: затоплен подвал, нет ключа):");
    }

    private boolean onProblemReason(TextContext ctx) {
        Integer orshId = ctx.session().remove(WAITING_PROBLEM_REASON);
        if (orshId == null) return false;

        // Режим ручного добавления нового шкафа ОРШ администратором
        if (orshId == ADD_MANUAL_ORSH_SENTINEL) {
            String res = orsh.addCabinetsManual(ctx.trimmed());
            if (res.startsWith("❌")) {
                ctx.session().put(WAITING_PROBLEM_REASON, ADD_MANUAL_ORSH_SENTINEL);
                ui.cancelPrompt(ctx.chatId(), res + "\n\nПопробуйте ввести еще раз:");
                return true;
            }
            menu.show(ctx.chatId(), ctx.role(), res);
            showPending(ctx.chatId(), ctx.role());
            return true;
        }

        orsh.markProblem(orshId, ctx.firstName(), ctx.text());
        menu.show(ctx.chatId(), ctx.role(), "⚠️ Причина зафиксирована. Этот ОРШ переведен в статус проблемных.");
        return true;
    }

    /** Фото осмотра: пересылается на почту руководству, шкаф вычёркивается из плана. */
    private boolean onPhoto(MessageContext ctx) {
        Integer orshId = ctx.session().remove(WAITING_PHOTO);
        if (orshId == null) return false;
        long chatId = ctx.chatId();
        menu.show(chatId, ctx.role(), "⏳ Скачиваю фото и отправляю на почту руководству...");

        File downloaded = null;
        File renamed = null;
        try {
            Optional<OrshCabinet> found = orsh.find(orshId);
            if (found.isEmpty()) {
                menu.show(chatId, ctx.role(), "❌ Шкаф не найден.");
                return true;
            }
            OrshCabinet cabinet = found.get();

            List<PhotoSize> photos = ctx.message().getPhoto();
            downloaded = messenger.download(photos.get(photos.size() - 1).getFileId());

            File attachment = downloaded;
            File target = new File(downloaded.getParentFile(), OrshView.photoFileName(cabinet));
            if (downloaded.renameTo(target)) {
                renamed = target;
                attachment = target;
            }

            email.send(OrshView.emailSubject(cabinet, ctx.firstName()), OrshView.emailBody(cabinet, ctx.firstName()), attachment);
            orsh.markCompleted(orshId, ctx.firstName());
            menu.show(chatId, ctx.role(), OrshView.photoSentText(cabinet));
        } catch (Exception e) {
            log.error("Ошибка отправки фото ОРШ {}", orshId, e);
            menu.show(chatId, ctx.role(), "❌ Ошибка при отправке фото: " + Html.esc(String.valueOf(e.getMessage())));
        } finally {
            deleteQuietly(downloaded);
            deleteQuietly(renamed);
        }
        return true;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("Не удалось удалить временный файл {}", file);
        }
    }
}