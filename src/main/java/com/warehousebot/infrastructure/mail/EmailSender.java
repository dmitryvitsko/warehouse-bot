package com.warehousebot.infrastructure.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Отправка писем руководству (фото осмотров ОРШ, отчёт по складу) через SMTP Яндекса.
 *
 * <p>В отличие от прежней версии заданы таймауты (иначе зависший SMTP-сервер блокировал поток навсегда)
 * и включена проверка имени хоста в сертификате сервера.
 */
public final class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private static final String TIMEOUT_MS = "20000";

    private final String from;
    private final String password;
    private final String to;

    public EmailSender(String from, String password, String to) {
        this.from = from;
        this.password = password;
        this.to = to;
    }

    /** Отправляет письмо с необязательным вложением. Ошибки пробрасываются, чтобы бот сообщил о сбое. */
    public void send(String subject, String text, File attachment) throws MessagingException, IOException {
        if (from == null || to == null) throw new MessagingException("Почта не настроена в config.properties");

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.yandex.ru");
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from, "Бот Склада ЦБР", "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject, "UTF-8");

        MimeBodyPart body = new MimeBodyPart();
        body.setText(text, "UTF-8");
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        if (attachment != null && attachment.exists()) {
            MimeBodyPart attach = new MimeBodyPart();
            attach.attachFile(attachment);
            multipart.addBodyPart(attach);
        }
        message.setContent(multipart);

        Transport.send(message);
        log.info("✅ Письмо успешно отправлено");
    }
}
