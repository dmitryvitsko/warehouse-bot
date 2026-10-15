package com.warehousebot.infrastructure.excel;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Разбор Excel-файлов. Заменяет ручной генератор тестового плана ОРШ (GenerateTestOrsh). */
class ExcelParserTest {

    @TempDir
    Path tmp;

    @Test
    void parsesOrshPlanWithNumericAndTextNumbers() throws Exception {
        File file = tmp.resolve("orsh.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("План ОРШ");
            row(sheet, 0, "Номер ОРШ", "Адрес", "Местоположение");
            row(sheet, 1, "12380372", "ул. Ленина, 17", "2 подъезд, лестничная клетка");
            Row numeric = sheet.createRow(2);
            numeric.createCell(0).setCellValue(42452);
            numeric.createCell(1).setCellValue("пр. Мировая, 144");
            numeric.createCell(2).setCellValue("подвал");
            write(wb, file);
        }

        List<ExcelParser.OrshRow> rows = ExcelParser.parseOrsh(file);

        assertEquals(2, rows.size());
        assertEquals(new ExcelParser.OrshRow("12380372", "ул. Ленина, 17", "2 подъезд, лестничная клетка"), rows.get(0));
        assertEquals("42452", rows.get(1).number());
    }

    @Test
    void toolNameMayBeNumericCell() throws Exception {
        File file = tmp.resolve("tools.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            row(sheet, 0, "Инв", "Название");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(1001);
            r.createCell(1).setCellValue(2000); // раньше getStringCellValue() ронял весь импорт
            write(wb, file);
        }

        List<ExcelParser.ToolRow> rows = ExcelParser.parseTools(file);

        assertEquals(List.of(new ExcelParser.ToolRow("2000", "1001", 1)), rows);
    }

    @Test
    void quantityConversionHasNoFloatingPointTail() {
        assertEquals(1001.0, ExcelParser.roundQty(1.001 * 1000));
        assertEquals(0.5, ExcelParser.roundQty(0.0005 * 1000));
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row r = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) r.createCell(i).setCellValue(values[i]);
    }

    private static void write(Workbook wb, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            wb.write(out);
        }
    }
}
