package com.warehousebot.bot.feature.orsh;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.OrshCabinet;
import com.warehousebot.domain.OrshProblem;
import com.warehousebot.domain.OrshStatistics;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры планового осмотра ОРШ. */
final class OrshView {

    /** Сколько адресов показывать в списке кнопками. */
    private static final int MAX_LIST_BUTTONS = 30;

    private OrshView() {
    }

    static String pendingListText(int count) {
        return "📸 <b>Осталось проверить: " + count + " шт.</b>\nВыберите адрес:";
    }

    static InlineKeyboardMarkup pendingListKeyboard(List<OrshCabinet> pending) {
        Keyboards.Rows rows = Keyboards.rows();
        for (OrshCabinet c : pending.subList(0, Math.min(MAX_LIST_BUTTONS, pending.size()))) {
            rows.add(btn("📍 " + Formats.cut(c.address(), 35), "ORSH_SEL:" + c.id()));
        }
        rows.add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE));
        return rows.build();
    }

    static String detailsText(OrshCabinet c) {
        return String.format("📸 <b>Выбран ОРШ-%s</b>\n📍 Адрес: %s\n🧭 Местоположение: %s\n\n👇 <b>Отправьте фото!</b>",
                Html.esc(c.number()), Html.esc(c.address()), Html.esc(c.location()));
    }

    static InlineKeyboardMarkup detailsKeyboard(int orshId) {
        return Keyboards.of(
                row(btn("⚠ Невозможно сделать фото", "ORSH_PROB:" + orshId)),
                row(btn("❌ Закрыть", Keyboards.CANCEL_PROMPT)));
    }

    /** Тема письма руководству (обычный текст, не HTML). */
    static String emailSubject(OrshCabinet c, String workerName) {
        return "Осмотр ОРШ-" + c.number() + " (" + workerName + ")";
    }

    static String emailBody(OrshCabinet c, String workerName) {
        return String.format("Плановый осмотр ОРШ\n\nНомер: %s\nАдрес: %s\nМестоположение: %s\n\nВыполнил: %s",
                c.number(), c.address(), c.location(), workerName);
    }

    /** Имя файла фото во вложении: «ОРШ номер - адрес.jpg» без недопустимых в имени файла символов. */
    static String photoFileName(OrshCabinet c) {
        String safeAddress = c.address().replaceAll("[\\\\/:*?\"<>|]", " ");
        return "ОРШ " + c.number() + " - " + safeAddress + ".jpg";
    }

    static String photoSentText(OrshCabinet c) {
        return "✅ <b>Фото успешно отправлено!</b>\nОРШ-" + Html.esc(c.number()) + " вычеркнут из плана осмотра.";
    }

    static String statisticsText(OrshStatistics s) {
        StringBuilder sb = new StringBuilder("📊 <b>Статистика осмотра ОРШ:</b>\n\n");
        sb.append(String.format("Всего в плане: <b>%d шт.</b>\n✅ Проверено: <b>%d шт.</b>\n⏳ Осталось: <b>%d шт.</b>\n⚠️ Проблемные: <b>%d шт.</b>\n\n",
                s.total(), s.completed(), s.pending(), s.problem()));
        if (s.problem() > 0) {
            sb.append("🚨 <b>Проблемные ОРШ:</b>\n");
            for (OrshProblem p : s.problems()) {
                sb.append(String.format("• <b>%s</b> — %s <i>(%s)</i>\n",
                        Html.esc(p.number()), Html.esc(String.valueOf(p.reason())), Html.esc(String.valueOf(p.inspectedBy()))));
            }
        }
        return sb.toString();
    }
}
