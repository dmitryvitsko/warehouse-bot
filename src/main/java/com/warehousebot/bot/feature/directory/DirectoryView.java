package com.warehousebot.bot.feature.directory;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.DirectoryContact;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Текст телефонного справочника: постоянные номера + добавленные сотрудниками по категориям 1–9. */
final class DirectoryView {

    private DirectoryView() {
    }

    static String directoryText(List<DirectoryContact> added) {
        Map<Integer, List<String>> dyn = new HashMap<>();
        for (DirectoryContact c : added) dyn.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(Html.esc(c.text()));

        StringBuilder sb = new StringBuilder("📞 <b>ТЕЛЕФОННЫЙ СПРАВОЧНИК</b>\n\n");
        sb.append("🏢 <b>Руководство ЛКЦ</b>\n▪ Нач. цеха Богино А.Е.: +375(17) 335-24-44\n▪️ Зам. нач. цеха Жук С.Г.: +375(17) 334-44-45\n");
        appendDynamic(sb, dyn, 1, "▪️ ");
        sb.append("\n👷‍♂ <b>ИТР УЛКС №2</b>\n▪️ Нач. УЛКС №2 Лазуко С.В.: +375(17) 200-02-98, +375(29) 501-01-11\n▪ Зам. нач. УЛКС №2 Журко А.Н.: +375(17) 264-93-74, +375(29) 231-69-23\n▪ Рук. каб. группы Прохорчик В.Ю.: +375(17) 200-40-40, +375(29) 176-58-88\n▪️ Рук. гр. технадзора Снитко А.В.: +375(33) 347-28-85\n▪ Рук. гр. малопарщиков Никитин Д.Д.: +375(44) 752-02-69\n▪ Рук. изм. группы Лабуза С.И.: +375(29) 701-88-29\n");
        appendDynamic(sb, dyn, 2, "▪️ ");
        sb.append("\n💻 <b>Технический отдел</b>\n▪ Рук. гр. технадзора Шашков В.П.: +375(29) 373-35-55\n▪️ Инж. технадзора Кононова Светлана: +375(29) 577-57-46\n▪ Магистральщики (Протасевич Юлия): +375(29) 560-80-82\n▪️ Профсоюзные дела (Луговцова Оксана): +375(29) 317-19-56\n");
        appendDynamic(sb, dyn, 3, "▪ ");
        sb.append("\n🗂 <b>Администрация</b>\n▪️ Профком (Нестерова Е.Ю.): +375(17) 359-45-70\n▪️ Бухгалтерия по ЗП (Ромашко Ю.Г.): +375(17) 359-45-20\n▪️ Специалист по кадрам (Субач Е.В.): +375(17) 359-45-03\n");
        appendDynamic(sb, dyn, 4, "▪ ");
        sb.append("\n🚗 <b>АТЦ</b>\n▪️ Начальник Савицкий А.В.: +375(17) 369-05-25, +375(33) 603-32-73\n▪️ Зам. начальника Болбас Р.А.: +375(17) 369-03-93, +375(29) 840-78-37\n▪ Инж. по БД Гузов А.П.: +375(17) 369-05-27, +375(29) 779-11-88\n");
        appendDynamic(sb, dyn, 5, "▪️ ");
        sb.append("\n🔌 <b>Станционщики (магистраль / проключения)</b>\n▪️ Инженер Ивашкевич Дарья: +375(29) 555-38-48\n");
        appendDynamic(sb, dyn, 6, "▪️ ");
        sb.append("\n📺 <b>Привязка СМЛ приставок</b>\n▪️ Дневное время (Ольга): +375(29) 788-84-98\n▪️ Вечернее время: +375(17) 334-56-24, +375(17) 328-46-56, +375(17) 252-44-55, +375(17) 359-41-10\n");
        appendDynamic(sb, dyn, 7, "▪️ ");
        sb.append("\n🎧 <b>Диспетчера и админы (закрытие заявок)</b>\n▪ Диспетчеры ЦАБР: +375(17) 274-30-10\n▪ Инженер ЦАБР: +375(17) 274-28-16\n▪ Админы: +375(17) 306-29-56, +375(33) 603-38-81\n▪ После 20:00: +375(17) 306-29-59\n▪ VPN: +375(17) 203-66-86\n");
        appendDynamic(sb, dyn, 8, "▪️ ");
        sb.append("\n📹 <b>Прочее</b>\n▪ Видеоконтроль (Андрей): +375(17) 359-40-30\n▪️ РСМОБ: +375(17) 357-96-30\n");
        appendDynamic(sb, dyn, 9, "▪️ ");
        return sb.toString();
    }

    private static void appendDynamic(StringBuilder sb, Map<Integer, List<String>> dyn, int category, String bullet) {
        for (String s : dyn.getOrDefault(category, List.of())) sb.append(bullet).append(s).append("\n");
    }

    static InlineKeyboardMarkup categoriesKeyboard() {
        return Keyboards.of(
                row(btn("🏢 Руководство", "DIR_CAT:1"), btn("👷‍♂ ИТР", "DIR_CAT:2")),
                row(btn("💻 Тех. отдел", "DIR_CAT:3"), btn("🗂 Админ.", "DIR_CAT:4")),
                row(btn("🚗 АТЦ", "DIR_CAT:5"), btn("🔌 Станционщики", "DIR_CAT:6")),
                row(btn("📺 Привязка", "DIR_CAT:7"), btn("🎧 Диспетчера", "DIR_CAT:8")),
                row(btn("📹 Прочее", "DIR_CAT:9")),
                row(btn("🔙 Назад", "DIR_BACK")));
    }
}
