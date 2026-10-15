package com.warehousebot.bot.feature.tm;

import java.util.ArrayList;
import java.util.List;

/** Состояние мастера «📝 Отправить отчёт» по заявке ТМ. Все шаги отображаются в одном сообщении (якоре). */
final class TmReportSession {

    enum Step { WAIT_TYPE, CONFIRM_FAST, WAIT_CODE, WAIT_RECEIPT_Q, WAIT_RECEIPT_NUM, WAIT_MATERIALS, WAIT_MAT_QTY,
        WAIT_REPORT_TEXT, WAIT_RETAIL, PREVIEW }

    /** Материал, добавленный к списанию. */
    record UsedMaterial(int materialId, String name, String unit, double priceWithVat, double quantity) {
    }

    final String taskId;
    final Integer anchorMsgId;
    Step step = Step.WAIT_TYPE;
    /** "PON", "SHPD" или "FAST_TRACK". */
    String type = "";
    String closingCode = "";
    boolean hasReceipt;
    String receiptNumber = "";
    final List<UsedMaterial> usedMaterials = new ArrayList<>();
    int waitingMaterialId = -1;
    String reportText = "";
    /** "Ритейл+", "Ритейл-" или пусто. */
    String retailStatus = "";
    /** Точечное редактирование из превью: после шага сразу возвращаемся в превью. */
    boolean editing;

    TmReportSession(String taskId, Integer anchorMsgId) {
        this.taskId = taskId;
        this.anchorMsgId = anchorMsgId;
    }

    /** Текст, который уйдёт в ТМ: код, квитанция, текст отчёта и ритейл через запятую. */
    String tmText() {
        List<String> parts = new ArrayList<>();
        if ("PON".equals(type) && !closingCode.isEmpty()) parts.add("КОД " + closingCode);
        if (hasReceipt && !receiptNumber.isEmpty()) parts.add("квитанция #" + receiptNumber);
        if (!reportText.isEmpty()) parts.add(reportText);
        if (!retailStatus.isEmpty()) parts.add(retailStatus);
        return String.join(", ", parts);
    }

    /** Сколько этого материала уже добавлено к списанию в отчёте. */
    double alreadyAdded(int materialId) {
        double sum = 0;
        for (UsedMaterial m : usedMaterials) {
            if (m.materialId() == materialId) sum += m.quantity();
        }
        return sum;
    }
}
