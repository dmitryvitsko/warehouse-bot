package com.warehousebot.bot.feature.tm;

import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.WriteOffItem;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.TmService;
import com.warehousebot.util.Formats;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.warehousebot.bot.feature.tm.TmReportSession.Step;

/**
 * Мастер отчёта по заявке ТМ: тип заявки → код/квитанция → материалы → текст → ритейл → превью → отправка.
 * При отправке материалы списываются из подотчёта сотрудника.
 */
public final class TmReportModule implements BotModule {

    private static final SessionKey<TmReportSession> REPORT = SessionKey.dialog("tm.report");

    private final Messenger messenger;
    private final TmService tm;
    private final StockFacade stock;
    private final TmModule tasks;

    public TmReportModule(Messenger messenger, TmService tm, StockFacade stock, TmModule tasks) {
        this.messenger = messenger;
        this.tm = tm;
        this.stock = stock;
        this.tasks = tasks;
    }

    @Override
    public void register(Routes r) {
        r.onCallbackPrefix("TM_REPORT:").when(tasks::canAccess).handle(this::start);
        r.onCallback(TmView.CANCEL_REPORT).handle(this::cancel);
        r.onCallbackPrefix("TM_REP_TYPE:").handle(this::chooseType);
        r.onCallbackPrefix("TM_REP_FAST_CONF:").handle(this::confirmFastTrack);
        r.onCallbackPrefix("TM_REP_CODE:").handle(this::chooseCode);
        r.onCallbackPrefix("TM_REP_HAS_REC:").handle(this::chooseHasReceipt);
        r.onCallbackPrefix("TM_REP_MAT:").handle(this::chooseMaterial);
        r.onCallback("TM_REP_MAT_DONE").handle(this::materialsDone);
        r.onCallback("TM_REP_PREVIEW").handle(this::backToPreview);
        r.onCallback("TM_REP_SEND").handle(this::send);
        r.onCallbackPrefix("TM_REP_RET:").handle(this::chooseRetail);
        r.onCallback("TM_REP_EDIT_TXT").handle(this::editText);
        r.onCallback("TM_REP_EDIT_MAT").handle(this::editMaterials);

        r.onText(TextStage.TM_REPORT_INPUT, this::onText);
        r.onCancel((ctx, explicitCancel) -> onTypedCancel(ctx));
    }

    // ------------------------------------------------------------------ начало и отмена

    private void start(CallbackContext ctx) {
        String taskId = ctx.payload("TM_REPORT:");
        tasks.clearListExcept(ctx.session(), ctx.chatId(), ctx.messageId());

        TmReportSession s = new TmReportSession(taskId, ctx.messageId());
        ctx.session().put(REPORT, s);
        updateUi(ctx.chatId(), s, TmView.reportStartText(taskId), TmView.reportTypeKeyboard());
    }

    private void cancel(CallbackContext ctx) {
        ctx.session().remove(REPORT);
        messenger.delete(ctx.chatId(), ctx.messageId());
        tasks.showInProgress(ctx.session(), ctx.chatId(), ctx.role());
    }

    /** «❌ Отменить» / «🔙 Назад», набранные текстом во время отчёта: возвращаемся к списку заявок. */
    private boolean onTypedCancel(TextContext ctx) {
        TmReportSession s = ctx.session().remove(REPORT);
        if (s == null) return false;
        if (s.anchorMsgId != null) messenger.delete(ctx.chatId(), s.anchorMsgId);
        tasks.showInProgress(ctx.session(), ctx.chatId(), ctx.role());
        return true;
    }

    // ------------------------------------------------------------------ шаги

    private void chooseType(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        String type = ctx.payload("TM_REP_TYPE:");
        s.type = type;
        switch (type) {
            case "FAST_TRACK" -> {
                s.step = Step.CONFIRM_FAST;
                updateUi(ctx.chatId(), s, TmView.FAST_CONFIRM, TmView.fastConfirmKeyboard(s.taskId));
            }
            case "PON" -> {
                s.step = Step.WAIT_CODE;
                updateUi(ctx.chatId(), s, TmView.PON_CODES, TmView.ponCodesKeyboard(s.taskId));
            }
            case "SHPD" -> {
                s.step = Step.WAIT_RECEIPT_Q;
                updateUi(ctx.chatId(), s, TmView.SHPD_RECEIPT_QUESTION, TmView.shpdReceiptKeyboard(s.taskId));
            }
            default -> {
                // неизвестный тип — как и раньше, ничего не меняем на экране
            }
        }
    }

    private void confirmFastTrack(CallbackContext ctx) {
        TmReportSession s = ctx.session().remove(REPORT);
        if (s == null || !"YES".equals(ctx.payload("TM_REP_FAST_CONF:"))) return;
        boolean ok = tm.performTask(ctx.chatId(), s.taskId, TmView.FAST_TRACK_TEXT);
        if (ok) updateUi(ctx.chatId(), s, TmView.fastSentText(s.taskId), Keyboards.single("❌ Закрыть", Keyboards.CANCEL_PROMPT));
    }

    private void chooseCode(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        String code = ctx.part(1);
        s.closingCode = code;
        if (code.equals("102")) askReceiptNumber(ctx.chatId(), s, TmView.RECEIPT_NUMBER_PON);
        else skipReceipt(ctx.chatId(), s);
    }

    private void chooseHasReceipt(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        if (ctx.part(1).equals("YES")) askReceiptNumber(ctx.chatId(), s, TmView.RECEIPT_NUMBER_SHPD);
        else skipReceipt(ctx.chatId(), s);
    }

    private void askReceiptNumber(long chatId, TmReportSession s, String prompt) {
        s.hasReceipt = true;
        s.step = Step.WAIT_RECEIPT_NUM;
        updateUi(chatId, s, prompt, null);
    }

    private void skipReceipt(long chatId, TmReportSession s) {
        s.hasReceipt = false;
        s.step = Step.WAIT_MATERIALS;
        showMaterialSelection(chatId, s);
    }

    private void chooseMaterial(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        s.waitingMaterialId = ctx.intPart(1);
        s.step = Step.WAIT_MAT_QTY;
        updateUi(ctx.chatId(), s, TmView.ENTER_QTY, null);
    }

    private void materialsDone(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        if (s.editing) {
            showPreview(ctx.chatId(), s);
        } else {
            s.step = Step.WAIT_REPORT_TEXT;
            updateUi(ctx.chatId(), s, TmView.ENTER_REPORT_TEXT, null);
        }
    }

    private void backToPreview(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        showPreview(ctx.chatId(), s);
    }

    private void chooseRetail(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        s.retailStatus = switch (ctx.part(1)) {
            case "PLUS" -> "Ритейл+";
            case "MINUS" -> "Ритейл-";
            default -> "";
        };
        s.step = Step.PREVIEW;
        renderPreview(ctx.chatId(), s);
    }

    private void editText(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        s.editing = true;
        s.step = Step.WAIT_REPORT_TEXT;
        updateUi(ctx.chatId(), s, TmView.EDIT_REPORT_TEXT, TmView.editTextKeyboard());
    }

    private void editMaterials(CallbackContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return;
        s.editing = true;
        s.usedMaterials.clear();
        s.step = Step.WAIT_MATERIALS;
        showMaterialSelection(ctx.chatId(), s);
    }

    // ------------------------------------------------------------------ ввод текста

    private boolean onText(TextContext ctx) {
        TmReportSession s = ctx.session().get(REPORT);
        if (s == null) return false;
        long chatId = ctx.chatId();
        switch (s.step) {
            case WAIT_RECEIPT_NUM -> {
                s.receiptNumber = ctx.trimmed();
                s.step = Step.WAIT_MATERIALS;
                showMaterialSelection(chatId, s);
                return true;
            }
            case WAIT_MAT_QTY -> {
                onMaterialQuantity(chatId, s, ctx.text());
                return true;
            }
            case WAIT_REPORT_TEXT -> {
                s.reportText = ctx.trimmed();
                if (s.editing) {
                    // Редактировали только текст — сразу в превью
                    showPreview(chatId, s);
                } else {
                    s.step = Step.WAIT_RETAIL;
                    updateUi(chatId, s, TmView.RETAIL_QUESTION, TmView.retailKeyboard());
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void onMaterialQuantity(long chatId, TmReportSession s, String text) {
        double qty;
        try {
            qty = Formats.parseUserNumber(text);
        } catch (NumberFormatException e) {
            updateUi(chatId, s, TmView.BAD_QTY, null);
            return;
        }
        if (!(qty > 0)) {
            updateUi(chatId, s, TmView.BAD_QTY, null);
            return;
        }
        Optional<HeldMaterial> held = stock.heldMaterial(chatId, s.waitingMaterialId);
        if (held.isPresent()) {
            HeldMaterial m = held.get();
            // Нельзя списать больше, чем числится на руках (с учётом уже добавленного в этот отчёт)
            double available = m.quantity() - s.alreadyAdded(m.materialId());
            if (qty > available + 1e-9) {
                updateUi(chatId, s, TmView.notEnoughOnHands(available, m.unit()), null);
                return;
            }
            s.usedMaterials.add(new TmReportSession.UsedMaterial(m.materialId(), m.name(), m.unit(), m.priceWithVat(), qty));
        }
        s.waitingMaterialId = -1;
        s.step = Step.WAIT_MATERIALS;
        showMaterialSelection(chatId, s);
    }

    // ------------------------------------------------------------------ отправка

    private void send(CallbackContext ctx) {
        TmReportSession s = ctx.session().remove(REPORT);
        if (s == null) return;
        long chatId = ctx.chatId();

        String tmText = s.tmText();
        boolean sentToTm = tm.performTask(chatId, s.taskId, tmText);

        List<String> failed = new ArrayList<>();
        for (TmReportSession.UsedMaterial m : s.usedMaterials) {
            WriteOffItem item = new WriteOffItem(m.materialId(), m.name(), m.unit(), m.priceWithVat(), m.quantity(),
                    s.hasReceipt, s.receiptNumber, "-", "-", "-", s.closingCode, "Закрытие заявки ТМ #" + s.taskId);
            String result = stock.writeOff(chatId, item);
            if (result != null && result.startsWith("❌")) failed.add(result);
        }

        updateUi(chatId, s, TmView.sendResultText(s.taskId, tmText, sentToTm, failed), TmView.backToTasksKeyboard());
    }

    // ------------------------------------------------------------------ экраны

    private void showPreview(long chatId, TmReportSession s) {
        s.editing = false;
        s.step = Step.PREVIEW;
        renderPreview(chatId, s);
    }

    private void renderPreview(long chatId, TmReportSession s) {
        messenger.sendOrEdit(chatId, s.anchorMsgId, TmView.previewText(s), TmView.previewKeyboard(s.taskId));
    }

    private void showMaterialSelection(long chatId, TmReportSession s) {
        List<HeldMaterial> held = stock.heldMaterials(chatId);
        messenger.sendOrEdit(chatId, s.anchorMsgId, TmView.materialSelectionText(s), TmView.materialSelectionKeyboard(s, held));
    }

    /** Обновляет окно мастера; без клавиатуры — показывает «❌ Отменить». */
    private void updateUi(long chatId, TmReportSession s, String text, InlineKeyboardMarkup markup) {
        if (s.anchorMsgId == null) return;
        messenger.edit(chatId, s.anchorMsgId, text, markup != null ? markup : TmView.cancelReportKeyboard());
    }
}
