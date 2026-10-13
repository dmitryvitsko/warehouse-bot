package com.warehousebot;

import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.config.AppConfig;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;

import java.util.concurrent.ScheduledExecutorService;

/** Общая инфраструктура, которую получают все функциональные модули при сборке приложения. */
public record AppContext(
        AppConfig config,
        Database db,
        Messenger messenger,
        SessionStore sessions,
        Ui ui,
        MainMenu menu,
        AccessGate gate,
        UserRepository users,
        UserService userService,
        ReturnRequestRepository returnRequests,
        EmailSender email,
        ScheduledExecutorService scheduler) {
}
