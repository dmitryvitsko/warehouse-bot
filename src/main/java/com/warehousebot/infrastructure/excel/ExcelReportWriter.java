package com.warehousebot.infrastructure.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Запись простых табличных отчётов в xlsx: жирная строка заголовков и автоширина колонок. */
public final class ExcelReportWriter {

    /**
     * Лист отчёта. Значения строк — {@link String}, {@link Number} или {@code null} (пустая ячейка).
     */
    public record SheetData(String name, List<String> headers, List<List<Object>> rows) {
    }

    private ExcelReportWriter() {
    }

    public static void write(File file, List<SheetData> sheets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            for (SheetData data : sheets) {
                Sheet sheet = workbook.createSheet(data.name());
                Row header = sheet.createRow(0);
                for (int i = 0; i < data.headers().size(); i++) {
                    Cell cell = header.createCell(i);
                    cell.setCellValue(data.headers().get(i));
                    cell.setCellStyle(headerStyle);
                }
                int rowIdx = 1;
                for (List<Object> values : data.rows()) {
                    Row row = sheet.createRow(rowIdx++);
                    for (int i = 0; i < values.size(); i++) {
                        Cell cell = row.createCell(i);
                        Object v = values.get(i);
                        if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                        else if (v != null) cell.setCellValue(v.toString());
                    }
                }
                for (int i = 0; i < data.headers().size(); i++) sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
    }
}
