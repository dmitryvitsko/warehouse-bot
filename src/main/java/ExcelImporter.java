import org.apache.poi.ss.usermodel.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class ExcelImporter {

    public static class MaterialRow {
        public String account;
        public String code;
        public String name;
        public String originalUnit; // Как в оборотке (км, тыс.шт)
        public String workUnit;     // Как видит монтер (м, шт)
        public double convFactor;   // Коэффициент (1000)
        public double priceWithVat; // Цена за рабочую единицу с НДС
        public double qty;          // Количество в рабочих единицах
        public double startQty;
        public double startSum;
        public double inQty;
        public double outQty;
        public double endQty;
        public double endSum;
        public String batchDate;
        public String batchInfo;
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
                        currentAccount = null;
                    }
                    continue;
                }

                // Конец блока счета
                if (colB.startsWith("Итого по счету") || colB.startsWith("Итого по")) {
                    currentAccount = null;
                    continue;
                }

                // Если мы внутри счета и строка похожа на товар (в колонке A число)
                if (currentAccount != null && isNumericCell(row.getCell(0)) && !colB.isEmpty()) {
                    String origUnit = getCellString(row.getCell(3)).trim();
                    double origPrice = getCellDouble(row.getCell(5));
                    double origEndQty = getCellDouble(row.getCell(12));
                    String name = getCellString(row.getCell(2));

                    // --- УМНЫЙ КОНВЕРТЕР ЕДИНИЦ ---
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
                        factor = 100.0; // 1 упаковка гильз = 100 шт
                        workUnit = "шт";
                    }

                    MaterialRow item = new MaterialRow();
                    item.account = currentAccount;
                    item.code = colB;
                    item.name = name;
                    item.originalUnit = origUnit;
                    item.workUnit = workUnit;
                    item.convFactor = factor;

                    // Сохраняем исходные данные для истории (чтобы бухгалтерии сходилось)
                    item.batchDate = getCellString(row.getCell(4));
                    item.startQty = getCellDouble(row.getCell(6));
                    item.startSum = getCellDouble(row.getCell(7));
                    item.inQty = getCellDouble(row.getCell(8));
                    item.outQty = getCellDouble(row.getCell(10));
                    item.endQty = origEndQty;
                    item.endSum = getCellDouble(row.getCell(13));

                    // Пересчитываем для бота в удобных единицах
                    item.qty = origEndQty * factor;
                    double priceWithoutVatPerWorkUnit = origPrice / factor;
                    item.priceWithVat = priceWithoutVatPerWorkUnit * 1.20; // + 20% НДС

                    parsedRows.add(item);
                }
            }

            if (parsedRows.isEmpty()) {
                return "❌ В файле не найдены позиции по счетам 10.01 и 10.05. Проверьте структуру таблицы.";
            }

            return DatabaseManager.saveImportedMaterials(parsedRows);

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении Excel-файла оборотной ведомости: " + e.getMessage();
        }
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
                    if (txt.contains(" (МЕСЯЦ)")) {
                        monthName = txt.replace(" (МЕСЯЦ)", "").trim();
                    }
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
    public static String importToolsSheet(File file) {
        List<DatabaseManager.ParsedTool> tools = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {

            // Берем первый лист
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);

            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Пропускаем строку с заголовками

                // Колонка B (индекс 1) - Название инструмента
                org.apache.poi.ss.usermodel.Cell nameCell = row.getCell(1);
                if (nameCell == null || nameCell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
                String name = nameCell.getStringCellValue().trim();

                // Колонка A (индекс 0) - Инвентарный номер
                org.apache.poi.ss.usermodel.Cell invCell = row.getCell(0);
                String invNumber = "";
                if (invCell != null) {
                    if (invCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        invNumber = invCell.getStringCellValue().trim();
                    } else if (invCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        // Если эксель решил, что номер это число, убираем нули после запятой
                        invNumber = String.valueOf((long) invCell.getNumericCellValue());
                    }
                }

                // Колонка L (индекс 11) - Количество
                org.apache.poi.ss.usermodel.Cell qtyCell = row.getCell(11);
                int qty = 1; // По умолчанию 1
                if (qtyCell != null && qtyCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    qty = (int) qtyCell.getNumericCellValue();
                }

                DatabaseManager.ParsedTool tool = new DatabaseManager.ParsedTool();
                tool.name = name;
                tool.invNumber = invNumber;
                tool.quantity = qty;
                tools.add(tool);
            }
            return DatabaseManager.saveImportedTools(tools);
        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении Excel файла с инструментом: " + e.getMessage();
        }
    }
    // Чтение плана ОРШ
    public static String importOrshSheet(File file) {
        List<DatabaseManager.ParsedOrsh> list = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {

            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Пропуск заголовка

                org.apache.poi.ss.usermodel.Cell numCell = row.getCell(0);
                if (numCell == null) continue;

                DatabaseManager.ParsedOrsh o = new DatabaseManager.ParsedOrsh();

                // Читаем как текст или как число без нулей
                if (numCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    o.number = String.valueOf((long) numCell.getNumericCellValue());
                } else {
                    o.number = numCell.getStringCellValue().trim();
                }

                org.apache.poi.ss.usermodel.Cell addrCell = row.getCell(1);
                o.address = (addrCell != null && addrCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) ? addrCell.getStringCellValue().trim() : "";

                org.apache.poi.ss.usermodel.Cell locCell = row.getCell(2);
                o.location = (locCell != null && locCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) ? locCell.getStringCellValue().trim() : "";

                if (!o.number.isEmpty()) list.add(o);
            }
            return DatabaseManager.saveImportedOrsh(list);
        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении файла ОРШ: " + e.getMessage();
        }
    }
}