import javax.mail.*;
import javax.mail.internet.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class EmailSender {
    private static final Properties config = new Properties();

    static {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            config.load(fis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Пробрасываем Exception наверх, чтобы WarehouseBot знал о сбоях сети или авторизации
    public static void sendOrshReport(String subject, String text, File photoFile) throws Exception {
        String from = config.getProperty("bot.email.login");
        String password = config.getProperty("bot.email.password");
        String to = config.getProperty("bot.email.target");

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.yandex.ru");
        props.put("mail.smtp.socketFactory.port", "465");
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.port", "465");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        // ИСПРАВЛЕНИЕ ЗДЕСЬ: Используем MimeMessage вместо базового Message
        MimeMessage message = new MimeMessage(session);

        // Теперь Java отлично видит второй аргумент "UTF-8"
        message.setFrom(new InternetAddress(from, "Бот Склада ЦБР", "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject, "UTF-8");

        // Текст письма с принудительной кодировкой
        MimeBodyPart messageBodyPart = new MimeBodyPart();
        messageBodyPart.setText(text, "UTF-8");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(messageBodyPart);

        // Вложение (фото)
        if (photoFile != null && photoFile.exists()) {
            MimeBodyPart attachPart = new MimeBodyPart();
            attachPart.attachFile(photoFile);
            multipart.addBodyPart(attachPart);
        }

        message.setContent(multipart);

        // Если здесь произойдет сбой (Яндекс не пустит), код прервется и выбросит ошибку
        Transport.send(message);
        System.out.println("✅ Письмо успешно отправлено на " + to);
    }
}