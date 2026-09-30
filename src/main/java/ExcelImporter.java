import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class ExcelImporter {

    public static class MaterialRow {
        public String account;      // Счет (10.01 или 10.05)
        public String code;         // Колонка B: Инвентарный номер
        public String name;         // Колонка C: Наименование
        public String unit;         // Колонка D: Единица измерения
        public String batchDate;    // Колонка E: Дата партии
        public double price;        // Колонка F: Цена за единицу
        public double startQty;     // Колонка G: Кол-во на начало месяца
        public double startSum;     // Колонка H: Сумма на начало месяца
        public double inQty;        // Колонка I: Приход (кол-во)
        public double outQty;       // Колонка K: Расход (кол-во)
        public double endQty;       // Колонка M: Остаток на конец месяца (кол-во)
        public double endSum;       // Колонка N: Стоимость остатка на конец месяца
    }

    public static String importTurnoverSheet(File file) {
        List<MaterialRow> parsedRows = new ArrayList<>();
        String currentAccount = null;

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                String colB = getCellString(row.getCell(1));

                // Проверяем переключение счета в колонке B
                if (colB.startsWith("Счёт:")) {
                    String acc = colB.replace("Счёт:", "").trim();
                    if ("10.01".equals(acc) || "10.05".equals(acc)) {
                        currentAccount = acc;
                    } else {
                        currentAccount = null; // Пропускаем 10.06, 10.10, 10.15 и др.
                    }
                    continue;
                }

                // Конец блока счета
                if (colB.startsWith("Итого по счету") || colB.startsWith("Итого по")) {
                    currentAccount = null;
                    continue;
                }

                // Если мы внутри счета 10.01 или 10.05 и в колонке A есть порядковый номер
                if (currentAccount != null && isNumericCell(row.getCell(0)) && !colB.isEmpty()) {
                    MaterialRow item = new MaterialRow();
                    item.account = currentAccount;
                    item.code = colB;                                  // Колонка B (индекс 1)
                    item.name = getCellString(row.getCell(2));         // Колонка C (индекс 2)
                    item.unit = getCellString(row.getCell(3));         // Колонка D (индекс 3)
                    item.batchDate = getCellString(row.getCell(4));    // Колонка E (индекс 4)
                    item.price = getCellDouble(row.getCell(5));        // Колонка F (индекс 5)
                    item.startQty = getCellDouble(row.getCell(6));     // Колонка G (индекс 6)
                    item.startSum = getCellDouble(row.getCell(7));     // Колонка H (индекс 7)
                    item.inQty = getCellDouble(row.getCell(8));        // Колонка I (индекс 8)
                    item.outQty = getCellDouble(row.getCell(10));      // Колонка K (индекс 10)
                    item.endQty = getCellDouble(row.getCell(12));      // Колонка M (индекс 12)
                    item.endSum = getCellDouble(row.getCell(13));      // Колонка N (индекс 13)

                    parsedRows.add(item);
                }
            }

            if (parsedRows.isEmpty()) {
                return "❌ В файле не найдены позиции по счетам 10.01 и 10.05. Проверьте структуру таблицы.";
            }

            return DatabaseManager.saveImportedMaterials(parsedRows);

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении Excel-файла: " + e.getMessage();
        }
    }

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

    private static String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == (long) d) yield String.valueOf((long) d);
                yield String.valueOf(d);
            }
            default -> "";
        };
    }

    private static double getCellDouble(Cell cell) {
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
    public static String importScheduleSheet(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int startRow = -1;
            String monthName = "Месяц";
            int yearVal = java.time.LocalDate.now().getYear();

            // Ищем заголовок "ФИО" и попутно вытаскиваем месяц и год из шапки таблицы
            for (int i = 0; i < Math.min(30, sheet.getLastRowNum()); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                for (Cell c : r) {
                    String txt = getCellString(c).toUpperCase();
                    // Ищем месяц по слову "(МЕСЯЦ)", как в вашем файле
                    if (txt.contains(" (МЕСЯЦ)")) {
                        monthName = txt.replace(" (МЕСЯЦ)", "").trim();
                    }
                    // Ищем год
                    if (txt.contains("202")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("202\\d").matcher(txt);
                        if (m.find()) {
                            yearVal = Integer.parseInt(m.group());
                        }
                    }
                    if (txt.contains("ФИО")) {
                        startRow = i + 1;
                    }
                }
            }

            if (startRow == -1) return "❌ Не удалось найти заголовок таблицы с 'ФИО'. Убедитесь, что это файл графика.";

            List<DatabaseManager.ScheduleDay> allDays = new ArrayList<>();

            for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;

                String timeLabel = getCellString(r.getCell(4)).toLowerCase();
                if (timeLabel.contains("нач")) {
                    String name = getCellString(r.getCell(1));
                    if (name.isEmpty()) continue;

                    Row endRow = sheet.getRow(i + 1);
                    if (endRow == null) continue;

                    for (int day = 1; day <= 31; day++) {
                        int colIdx = 4 + day;
                        String startStr = getTimeString(r.getCell(colIdx)).trim();
                        String endStr = getTimeString(endRow.getCell(colIdx)).trim();

                        if (startStr.isEmpty() && endStr.isEmpty()) continue;

                        DatabaseManager.ScheduleDay sd = new DatabaseManager.ScheduleDay();
                        sd.excelName = name;
                        sd.monthName = monthName;
                        sd.yearVal = yearVal;
                        sd.dayNum = day;
                        sd.statusCode = "";
                        sd.startTime = "";
                        sd.endTime = "";

                        String sUpper = startStr.toUpperCase();
                        if (sUpper.equals("В") || sUpper.equals("В ")) {
                            sd.statusCode = "В";
                        } else if (sUpper.equals("О") || sUpper.equals("О ")) {
                            sd.statusCode = "О";
                        } else if (sUpper.equals("Д") || sUpper.equals("Д ")) {
                            sd.statusCode = "Д";
                        } else {
                            sd.startTime = startStr;
                            sd.endTime = endStr;
                        }
                        allDays.add(sd);
                    }
                }
            }

            if (allDays.isEmpty()) return "❌ Не найдено расписание сотрудников. Проверьте формат файла.";
            return DatabaseManager.saveSchedule(allDays);

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении графика: " + e.getMessage();
        }
    }

    private static String getTimeString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return new java.text.SimpleDateFormat("HH:mm").format(cell.getDateCellValue());
            }
        }
        return "";
    }
}