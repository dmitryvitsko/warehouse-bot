package com.warehousebot.bot.feature.admin;

import com.warehousebot.bot.core.Messenger;
import com.warehousebot.infrastructure.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.util.regex.Pattern;

/** Ежедневная резервная копия базы (VACUUM INTO), отправляемая главному администратору в Telegram. */
final class BackupJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

    /** Имя файла генерируется нами, но перед вставкой в SQL всё равно сверяется с шаблоном. */
    private static final Pattern SAFE_NAME = Pattern.compile("backup_\\d{4}-\\d{2}-\\d{2}\\.db");

    private final Database db;
    private final Messenger messenger;
    private final long adminId;

    BackupJob(Database db, Messenger messenger, long adminId) {
        this.db = db;
        this.messenger = messenger;
        this.adminId = adminId;
    }

    @Override
    public void run() {
        File backup = new File("backup_" + LocalDate.now() + ".db");
        try {
            if (!SAFE_NAME.matcher(backup.getName()).matches()) {
                log.error("Недопустимое имя файла резервной копии: {}", backup.getName());
                return;
            }
            if (backup.exists() && !backup.delete()) {
                log.warn("Не удалось удалить старый файл {}", backup);
            }
            db.execute("VACUUM INTO '" + backup.getName() + "'");
            messenger.sendDocument(adminId, backup);
        } catch (Exception e) {
            // Исключение не должно отменить следующие запуски по расписанию.
            log.error("Ошибка ежедневной резервной копии", e);
        } finally {
            if (backup.exists() && !backup.delete()) {
                log.warn("Не удалось удалить файл резервной копии {}", backup);
            }
        }
    }
}
