package com.warehousebot.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Единая точка чтения config.properties.
 * Ключи совпадают с прежними, поэтому существующий файл конфигурации подходит без изменений.
 */
public final class AppConfig {

    /** Администратор по умолчанию — значение, которое раньше было зашито в DatabaseManager.ADMIN_ID. */
    private static final long DEFAULT_ADMIN_ID = 129265455L;

    private final Properties props;

    private AppConfig(Properties props) {
        this.props = props;
    }

    public static AppConfig load(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("❌ Ошибка: не найден файл " + file.toAbsolutePath() + " в корне проекта!", e);
        }
        return new AppConfig(props);
    }

    public static AppConfig of(Properties props) {
        return new AppConfig(props);
    }

    public String botToken() { return required("bot.token"); }
    public String botUsername() { return required("bot.username"); }

    public long adminId() {
        String v = props.getProperty("bot.admin.id");
        return v == null || v.isBlank() ? DEFAULT_ADMIN_ID : Long.parseLong(v.trim());
    }

    public String tmApiUrl() { return props.getProperty("tm.api.url"); }

    public String emailLogin() { return props.getProperty("bot.email.login"); }
    public String emailPassword() { return props.getProperty("bot.email.password"); }
    public String emailTarget() { return props.getProperty("bot.email.target"); }

    public String dbUrl() { return props.getProperty("db.url", "jdbc:sqlite:warehouse.db"); }

    public int dbPoolSize() { return intProp("db.pool.size", 8); }
    public int workerThreads() { return intProp("bot.worker.threads", 16); }

    private int intProp(String key, int def) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private String required(String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("❌ В config.properties не задан параметр " + key);
        return v.trim();
    }
}
