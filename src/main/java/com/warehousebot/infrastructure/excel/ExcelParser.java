package com.warehousebot.infrastructure.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение Excel-файлов, которые загружает администратор. Только разбор — сохранением занимаются сервисы.
 * Форматы файлов не изменились.
 */
public final class ExcelParser {

    /** Позиция оборотной ведомости (счета 10.01 и 10.05), количество и цена — в рабочих единицах. */
    public record MaterialRow(String account, String code, String name, String originalUnit, String workUnit,
                              double convFactor, double priceWithVat, double qty,
                              double startQty, double startSum, double inQty, double outQty,
                              double endQty, double endSum, String batchDate) {
    }

    /** День графика сотрудника: либо статус (В/О/Д), либо время начала и конца смены. */
    public record ScheduleRow(String excelName, int day, String statusCode, String startTime, String endTime) {
    }

    public record ToolRow(String name, String invNumber, int quantity) {
    }

    public record OrshRow(String number, String address, String location) {
    }

    private ExcelParser() {
    }

    // ------------------------------------------------------------------ оборотная ведомость

    public static List<MaterialRow> parseTurnover(File file) throws IOException {
        List<MaterialRow> rows = new ArrayList<>();
        String currentAccount = null;

        try (FileInputStream fis = new FileInputStream(file); Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                String colB = cellString(row.getCell(1));

                if (colB.startsWith("Счёт:")) {
                    String acc = colB.replace("Счёт:", "").trim();
                    currentAccount = ("10.01".equals(acc) || "10.05".equals(acc)) ? acc : null;
                    continue;
                }
                if (colB.startsWith("Итого по счету") || colB.startsWith("Итого по")) {
                    currentAccount = null;
                    continue;
                }
                if (currentAccount == null || !isNumericCell(row.getCell(0)) || colB.isEmpty()) continue;

                String origUnit = cellString(row.getCell(3)).trim();
                double origPrice = cellDouble(row.getCell(5));
                double origEndQty = cellDouble(row.getCell(12));
                String name = cellString(row.getCell(2));

                // Перевод «бухгалтерских» единиц в рабочие: км → м, тыс. шт → шт, упаковка гильз → шт.
                double factor = 1.0;
                String workUnit = origUnit;
                String u = origUnit.toLowerCase();
                String n = name.toLowerCase();
                if (u.contains("км") || u.contains("километр")) {
                    factor = 1000.0;
                    workUnit = "м";
                } else if (u.contains("тыс")) {
                    factor = 1000.0;
                    workUnit = "шт";
                } else if (n.contains("гильз") && (u.contains("уп") || u.contains("упак"))) {
                    factor = 100.0;
                    workUnit = "шт";
                }

                double priceWithVat = (origPrice / factor) * 1.20; // + 20% НДС
                rows.add(new MaterialRow(currentAccount, colB, name, origUnit, workUnit, factor, priceWithVat,
                        roundQty(origEndQty * factor),
                        cellDouble(row.getCell(6)), cellDouble(row.getCell(7)), cellDouble(row.getCell(8)),
                        cellDouble(row.getCell(10)), origEndQty, cellDouble(row.getCell(13)),
                        cellString(row.getCell(4))));
            }
        }
        return rows;
    }

    /** Убирает «хвосты» двоичной арифметики: 1.001 км × 1000 = 1001 м, а не 1000.9999999999999. */
    static double roundQty(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    // ------------------------------------------------------------------ график работ

    public static List<ScheduleRow> parseSchedule(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file); Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            int startRow = -1;

            // Ищем заголовок «ФИО» (месяц и год администратор выбирает в боте).
            for (int i = 0; i < Math.min(30, sheet.getLastRowNum()); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                for (Cell c : r) {
                    if (cellString(c).toUpperCase().contains("ФИО")) {
                        startRow = i + 1;
                        break;
                    }
                }
                if (startRow != -1) break;
            }
            if (startRow == -1) throw new Exception("Не удалось найти заголовок таблицы с 'ФИО'.");

            List<ScheduleRow> days = new ArrayList<>();
            for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                if (!cellString(r.getCell(4)).toLowerCase().contains("нач")) continue;

                String name = cellString(r.getCell(1));
                if (name.isEmpty()) continue;
                Row endRow = sheet.getRow(i + 1);
                if (endRow == null) continue;

                for (int day = 1; day <= 31; day++) {
                    int col = 4 + day;
                    String startStr = timeString(r.getCell(col)).trim();
                    String endStr = timeString(endRow.getCell(col)).trim();
                    if (startStr.isEmpty() && endStr.isEmpty()) continue;

                    String upper = startStr.toUpperCase();
                    if (upper.equals("В") || upper.equals("О") || upper.equals("Д")) {
                        days.add(new ScheduleRow(name, day, upper, "", ""));
                    } else {
                        days.add(new ScheduleRow(name, day, "", startStr, endStr));
                    }
                }
            }
            if (days.isEmpty()) throw new Exception("Не найдено расписание сотрудников.");
            return days;
        }
    }

    // ------------------------------------------------------------------ инструмент

    public static List<ToolRow> parseTools(File file) throws IOException {
        List<ToolRow> tools = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file); Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                Cell nameCell = row.getCell(1);
                if (nameCell == null || nameCell.getCellType() == CellType.BLANK) continue;
                String name = cellString(nameCell);

                String invNumber = "";
                Cell invCell = row.getCell(0);
                if (invCell != null) {
                    if (invCell.getCellType() == CellType.STRING) invNumber = invCell.getStringCellValue().trim();
                    else if (invCell.getCellType() == CellType.NUMERIC) invNumber = String.valueOf((long) invCell.getNumericCellValue());
                }

                int qty = 1;
                Cell qtyCell = row.getCell(11);
                if (qtyCell != null && qtyCell.getCellType() == CellType.NUMERIC) qty = (int) qtyCell.getNumericCellValue();

                tools.add(new ToolRow(name, invNumber, qty));
            }
        }
        return tools;
    }

    // ------------------------------------------------------------------ план ОРШ

    public static List<OrshRow> parseOrsh(File file) throws IOException {
        List<OrshRow> list = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file); Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                Cell numCell = row.getCell(0);
                if (numCell == null) continue;

                String number = numCell.getCellType() == CellType.NUMERIC
                        ? String.valueOf((long) numCell.getNumericCellValue())
                        : cellString(numCell);
                Cell addrCell = row.getCell(1);
                String address = (addrCell != null && addrCell.getCellType() == CellType.STRING) ? addrCell.getStringCellValue().trim() : "";
                Cell locCell = row.getCell(2);
                String location = (locCell != null && locCell.getCellType() == CellType.STRING) ? locCell.getStringCellValue().trim() : "";

                if (!number.isEmpty()) list.add(new OrshRow(number, address, location));
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ ячейки

    private static boolean isNumericCell(Cell cell) {
        if (cell == null) return false;
        if (cell.getCellType() == CellType.NUMERIC) return true;
        if (cell.getCellType() == CellType.STRING) {
            try {
                Double.parseDouble(cell.getStringCellValue().trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** Текст ячейки (для формул — сохранённый результат); целые числа без «.0». Пустая строка для прочих типов. */
    static String cellString(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> "";
        };
    }

    private static double cellDouble(Cell cell) {
        if (cell == null) return 0.0;
        return switch (cell.getCellType()) {
            case NUMERIC -> cell.getNumericCellValue();
            case FORMULA -> {
                try {
                    yield cell.getNumericCellValue();
                } catch (Exception e) {
                    yield 0.0;
                }
            }
            case STRING -> {
                try {
                    yield Double.parseDouble(cell.getStringCellValue().trim().replace(",", "."));
                } catch (NumberFormatException e) {
                    yield 0.0;
                }
            }
            default -> 0.0;
        };
    }

    private static String timeString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return new SimpleDateFormat("HH:mm").format(cell.getDateCellValue());
        }
        return "";
    }
}
