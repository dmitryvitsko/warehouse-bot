package com.warehousebot.bot.feature.admin;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.MessageContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.service.OrshService;
import com.warehousebot.service.ScheduleFacade;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.ToolFacade;
import com.warehousebot.util.Html;
import com.warehousebot.util.Months;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Document;

import java.io.File;
import java.time.LocalDate;

/** «📥 Загрузки (Excel)»: выбор базы для обновления и приём файла от администратора. */
public final class UploadsModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(UploadsModule.class);

    /**
     * Что администратор сейчас загружает: TURNOVER, TOOLS, ORSH, SCHEDULE:год:месяц;
     * WAIT_BROADCAST_TEXT — ожидается текст рассылки (см. {@link AdminModule}).
     */
    static final SessionKey<String> FILE_WAIT_STATE = SessionKey.dialog("admin.fileWaitState");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final StockFacade stock;
    private final ToolFacade tools;
    private final ScheduleFacade schedule;
    private final OrshService orsh;

    public UploadsModule(Messenger messenger, Ui ui, MainMenu menu, StockFacade stock, ToolFacade tools,
                         ScheduleFacade schedule, OrshService orsh) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.stock = stock;
        this.tools = tools;
        this.schedule = schedule;
        this.orsh = orsh;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.ADMIN_UPLOADS, ctx -> {
            if (ctx.isAdmin()) messenger.send(ctx.chatId(), AdminView.UPLOADS_MENU_TEXT, AdminView.uploadsKeyboard());
        });
        r.onCommand(AdminView.UPLOAD_ORSH, ctx -> waitFile(ctx, "ORSH", "📁 Отправьте файл ПЛАНА ОРШ (Excel)."));
        r.onCommand(AdminView.UPLOAD_TURNOVER, ctx -> waitFile(ctx, "TURNOVER", "📎 Отправьте файл ОБОРОТНОЙ ВЕДОМОСТИ."));
        r.onCommand(AdminView.UPLOAD_TOOLS, ctx -> waitFile(ctx, "TOOLS", "🪛 Отправьте файл базы ИНСТРУМЕНТА."));
        r.onCommand(AdminView.UPLOAD_SCHEDULE, ctx -> {
            if (ctx.isAdmin()) {
                messenger.send(ctx.chatId(), "🗓 <b>На какой месяц вы загружаете график?</b>\nВыберите из списка:",
                        AdminView.scheduleMonthsKeyboard(LocalDate.now()));
            }
        });
        r.onCallbackPrefix("UPL_SCHED:").adminOnly().handle(this::chooseScheduleMonth);
        r.onDocument(this::onDocument);
    }

    private void waitFile(TextContext ctx, String state, String prompt) {
        if (!ctx.isAdmin()) return;
        ctx.session().put(FILE_WAIT_STATE, state);
        ui.cancelPrompt(ctx.chatId(), prompt);
    }

    private void chooseScheduleMonth(CallbackContext ctx) {
        String year = ctx.part(1);
        String month = ctx.part(2);
        ctx.session().put(FILE_WAIT_STATE, "SCHEDULE:" + year + ":" + month);
        ui.cancelPrompt(ctx.chatId(), "🗓 Отправьте файл <b>ГРАФИКА РАБОТ</b> (Excel) на <b>"
                + Months.name(Integer.parseInt(month)) + " " + year + "</b> прямо в этот чат.");
    }

    /** Файл от администратора (роль, привязка и «входящие» уже проверены диспетчером). */
    private boolean onDocument(MessageContext ctx) {
        long chatId = ctx.chatId();
        String state = ctx.session().find(FILE_WAIT_STATE).orElse("");
        if (state.isEmpty()) {
            menu.show(chatId, ctx.role(), "Сначала нажмите кнопку загрузки в меню, а затем отправьте файл.");
            return true;
        }

        Document doc = ctx.message().getDocument();
        String fileName = doc.getFileName() != null ? doc.getFileName().toLowerCase() : "";
        if (!fileName.endsWith(".xls") && !fileName.endsWith(".xlsx")) {
            menu.show(chatId, ctx.role(), "❌ Пожалуйста, отправьте файл в формате <b>.xls</b> или <b>.xlsx</b>.");
            return true;
        }

        menu.show(chatId, ctx.role(), "⏳ Загружаю и обрабатываю файл...");
        File localFile = null;
        try {
            localFile = messenger.download(doc.getFileId());
            String result = importFile(state, localFile);
            ctx.session().remove(FILE_WAIT_STATE);
            menu.show(chatId, ctx.role(), result);
        } catch (Exception e) {
            log.error("Ошибка загрузки файла ({})", state, e);
            menu.show(chatId, ctx.role(), "❌ Ошибка при скачивании/обработке файла: " + Html.esc(String.valueOf(e.getMessage())));
        } finally {
            if (localFile != null && localFile.exists() && !localFile.delete()) {
                log.warn("Не удалось удалить временный файл {}", localFile);
            }
        }
        return true;
    }

    private String importFile(String state, File file) throws Exception {
        if ("TURNOVER".equals(state)) return stock.importTurnover(file);
        if (state.startsWith("SCHEDULE:")) {
            String[] p = state.split(":");
            return schedule.importSchedule(file, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        if ("TOOLS".equals(state)) return tools.importTools(file);
        if ("ORSH".equals(state)) return orsh.importPlan(file);
        return "❌ Неизвестное состояние загрузки файла.";
    }
}
