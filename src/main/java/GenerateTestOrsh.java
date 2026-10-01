import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileOutputStream;

public class GenerateTestOrsh {
    public static void main(String[] args) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("План ОРШ");

            // Тестовые данные (Заголовок + 5 шкафов)
            String[][] data = {
                    {"Номер ОРШ", "Адрес", "Местоположение"},
                    {"12380372", "ул. Ленина, 17", "2 подъезд, лестничная клетка"},
                    {"42452", "пр. Мировая, 144", "подвал, ключ от ЖЭС в 15 кв."},
                    {"55253", "ул. Победителей, 110", "1 подъезд, тамбур"},
                    {"6363", "ул. Горецкого, 55", "фасад здания, с торца"},
                    {"636346", "пр. Свободы, 13", "чердак, доступ через 5 подъезд"}
            };

            int rowNum = 0;
            for (String[] rowData : data) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(rowData[0]);
                row.createCell(1).setCellValue(rowData[1]);
                row.createCell(2).setCellValue(rowData[2]);
            }

            // Автоматически расширяем колонки для красоты
            sheet.setColumnWidth(0, 15 * 256);
            sheet.setColumnWidth(1, 30 * 256);
            sheet.setColumnWidth(2, 40 * 256);

            // Сохраняем файл в корень проекта
            try (FileOutputStream out = new FileOutputStream("test_orsh_plan.xlsx")) {
                workbook.write(out);
            }

            System.out.println("✅ Готово! Ищите файл test_orsh_plan.xlsx в корне вашего проекта.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}