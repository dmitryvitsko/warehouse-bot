import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class ExcelReportGenerator {

    public static File generateMonthlyReport() {
        File file = new File("Otchet_Sklad.xlsx");

        try (Workbook workbook = new XSSFWorkbook();
             Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // Стиль для жирных заголовков таблиц
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // =========================================================================
            // ЛИСТ 1: Списания ПО КВИТАНЦИИ (Платные)
            // =========================================================================
            Sheet sheetPaid = workbook.createSheet("1. По квитанциям (Платные)");
            String[] colsPaid = {"Дата", "ФИО Мастера", "Код", "Материал", "Кол-во", "Ед.", "Сумма с НДС (руб)", "№ Квитанции", "Телефон заявки", "№ Договора", "Адрес"};
            createHeader(sheetPaid, colsPaid, headerStyle);

            ResultSet rsPaid = stmt.executeQuery("""
                SELECT datetime(t.created_at, 'localtime') as dt, u.full_name, m.code, m.name,
                       t.quantity, m.work_unit, t.total_sum_with_vat,
                       t.receipt_number, t.phone_number, t.contract_number, t.subscriber_address
                FROM transactions t
                JOIN users u ON t.user_id = u.id
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF' AND t.is_paid_receipt = 1
                ORDER BY t.created_at DESC
            """);

            int rowIdx = 1;
            while (rsPaid.next()) {
                Row row = sheetPaid.createRow(rowIdx++);
                row.createCell(0).setCellValue(rsPaid.getString("dt"));
                row.createCell(1).setCellValue(rsPaid.getString("full_name"));
                row.createCell(2).setCellValue(rsPaid.getString("code"));
                row.createCell(3).setCellValue(rsPaid.getString("name"));
                row.createCell(4).setCellValue(rsPaid.getDouble("quantity"));
                row.createCell(5).setCellValue(rsPaid.getString("work_unit"));
                row.createCell(6).setCellValue(rsPaid.getDouble("total_sum_with_vat"));
                row.createCell(7).setCellValue(rsPaid.getString("receipt_number"));
                row.createCell(8).setCellValue(rsPaid.getString("phone_number"));
                row.createCell(9).setCellValue(rsPaid.getString("contract_number"));
                row.createCell(10).setCellValue(rsPaid.getString("subscriber_address"));
            }
            autoSizeColumns(sheetPaid, colsPaid.length);

            // =========================================================================
            // ЛИСТ 2: Списания БЕЗ КВИТАНЦИИ (Технический расход / крысы / износ)
            // =========================================================================
            Sheet sheetFree = workbook.createSheet("2. Без квитанций (Тех.расход)");
            String[] colsFree = {"Дата", "ФИО Мастера", "Код", "Материал", "Кол-во", "Ед.", "Код закрытия", "Телефон заявки", "№ Договора", "Адрес", "Причина списания"};
            createHeader(sheetFree, colsFree, headerStyle);

            ResultSet rsFree = stmt.executeQuery("""
                SELECT datetime(t.created_at, 'localtime') as dt, u.full_name, m.code, m.name,
                       t.quantity, m.work_unit, t.closing_code, t.phone_number, t.contract_number, t.subscriber_address, t.write_off_reason
                FROM transactions t
                JOIN users u ON t.user_id = u.id
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF' AND t.is_paid_receipt = 0
                ORDER BY t.created_at DESC
            """);

            rowIdx = 1;
            while (rsFree.next()) {
                Row row = sheetFree.createRow(rowIdx++);
                row.createCell(0).setCellValue(rsFree.getString("dt"));
                row.createCell(1).setCellValue(rsFree.getString("full_name"));
                row.createCell(2).setCellValue(rsFree.getString("code"));
                row.createCell(3).setCellValue(rsFree.getString("name"));
                row.createCell(4).setCellValue(rsFree.getDouble("quantity"));
                row.createCell(5).setCellValue(rsFree.getString("work_unit"));
                row.createCell(6).setCellValue(rsFree.getString("closing_code"));
                row.createCell(7).setCellValue(rsFree.getString("phone_number"));
                row.createCell(8).setCellValue(rsFree.getString("contract_number"));
                row.createCell(9).setCellValue(rsFree.getString("subscriber_address"));
                row.createCell(10).setCellValue(rsFree.getString("write_off_reason"));
            }
            autoSizeColumns(sheetFree, colsFree.length);

            // =========================================================================
            // ЛИСТ 3: Сводная ведомость расхода (с обратным пересчетом в км и тыс. шт)
            // =========================================================================
            Sheet sheetSum = workbook.createSheet("3. Сводная для бухгалтерии");
            String[] colsSum = {"Код", "Наименование", "По квитанциям", "Без квитанций", "ВСЕГО (раб. ед.)", "Раб. ед.", "ВСЕГО для бух. ведомости", "Бух. ед."};
            createHeader(sheetSum, colsSum, headerStyle);

            ResultSet rsSum = stmt.executeQuery("""
                SELECT m.code, m.name, m.work_unit, m.acc_unit, m.conversion_factor,
                       SUM(CASE WHEN t.is_paid_receipt = 1 THEN t.quantity ELSE 0 END) as paid_qty,
                       SUM(CASE WHEN t.is_paid_receipt = 0 THEN t.quantity ELSE 0 END) as free_qty,
                       SUM(t.quantity) as total_work_qty
                FROM transactions t
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF'
                GROUP BY m.id
                ORDER BY m.name
            """);

            rowIdx = 1;
            while (rsSum.next()) {
                Row row = sheetSum.createRow(rowIdx++);
                double totalWork = rsSum.getDouble("total_work_qty");
                double factor = rsSum.getDouble("conversion_factor");
                double totalAcc = totalWork / factor; // Переводим метры обратно в км (или шт в тыс. шт)

                row.createCell(0).setCellValue(rsSum.getString("code"));
                row.createCell(1).setCellValue(rsSum.getString("name"));
                row.createCell(2).setCellValue(rsSum.getDouble("paid_qty"));
                row.createCell(3).setCellValue(rsSum.getDouble("free_qty"));
                row.createCell(4).setCellValue(totalWork);
                row.createCell(5).setCellValue(rsSum.getString("work_unit"));
                row.createCell(6).setCellValue(totalAcc);
                row.createCell(7).setCellValue(rsSum.getString("acc_unit"));
            }
            autoSizeColumns(sheetSum, colsSum.length);

            // =========================================================================
            // ЛИСТ 4: Текущие остатки (На складе МОЛ + На руках у мастеров)
            // =========================================================================
            Sheet sheetBal = workbook.createSheet("4. Остатки (Склад и Мастера)");
            String[] colsBal = {"Где числится", "Код", "Материал", "Остаток (раб. ед.)", "Ед.", "Цена с НДС (руб)", "Остаток в бух. ед.", "Бух. ед."};
            createHeader(sheetBal, colsBal, headerStyle);

            rowIdx = 1;
            // Сначала выводим остатки на главном складе МОЛ
            ResultSet rsWh = stmt.executeQuery("SELECT code, name, warehouse_qty, work_unit, price_with_vat, conversion_factor, acc_unit FROM materials WHERE warehouse_qty > 0");
            while (rsWh.next()) {
                Row row = sheetBal.createRow(rowIdx++);
                double qty = rsWh.getDouble("warehouse_qty");
                double factor = rsWh.getDouble("conversion_factor");
                row.createCell(0).setCellValue("📦 СКЛАД МОЛ");
                row.createCell(1).setCellValue(rsWh.getString("code"));
                row.createCell(2).setCellValue(rsWh.getString("name"));
                row.createCell(3).setCellValue(qty);
                row.createCell(4).setCellValue(rsWh.getString("work_unit"));
                row.createCell(5).setCellValue(rsWh.getDouble("price_with_vat"));
                row.createCell(6).setCellValue(qty / factor);
                row.createCell(7).setCellValue(rsWh.getString("acc_unit"));
            }

            // Затем выводим остатки на руках у каждого мастера пофамильно
            ResultSet rsEmp = stmt.executeQuery("""
                SELECT u.full_name, m.code, m.name, b.quantity, m.work_unit, m.price_with_vat, m.conversion_factor, m.acc_unit
                FROM employee_balances b
                JOIN users u ON b.user_id = u.id
                JOIN materials m ON b.material_id = m.id
                WHERE b.quantity > 0
                ORDER BY u.full_name, m.name
            """);
            while (rsEmp.next()) {
                Row row = sheetBal.createRow(rowIdx++);
                double qty = rsEmp.getDouble("quantity");
                double factor = rsEmp.getDouble("conversion_factor");
                row.createCell(0).setCellValue("👤 " + rsEmp.getString("full_name"));
                row.createCell(1).setCellValue(rsEmp.getString("code"));
                row.createCell(2).setCellValue(rsEmp.getString("name"));
                row.createCell(3).setCellValue(qty);
                row.createCell(4).setCellValue(rsEmp.getString("work_unit"));
                row.createCell(5).setCellValue(rsEmp.getDouble("price_with_vat"));
                row.createCell(6).setCellValue(qty / factor);
                row.createCell(7).setCellValue(rsEmp.getString("acc_unit"));
            }
            autoSizeColumns(sheetBal, colsBal.length);

            // Записываем книгу в файл
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            return file;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void createHeader(Sheet sheet, String[] columns, CellStyle style) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns[i]);
            cell.setCellStyle(style);
        }
    }

    private static void autoSizeColumns(Sheet sheet, int count) {
        for (int i = 0; i < count; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}