package com.warehousebot;

import com.warehousebot.bot.WarehouseBot;
import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.ChatExecutor;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TelegramMessenger;
import com.warehousebot.bot.core.UpdateDispatcher;
import com.warehousebot.bot.feature.core.CoreModule;
import com.warehousebot.bot.feature.admin.AdminFeature;
import com.warehousebot.bot.feature.directory.DirectoryModule;
import com.warehousebot.bot.feature.inventory.InventoryModule;
import com.warehousebot.bot.feature.schedule.ScheduleFeature;
import com.warehousebot.bot.feature.stock.StockFeature;
import com.warehousebot.bot.feature.tm.TmFeature;
import com.warehousebot.bot.feature.tools.ToolsFeature;
import com.warehousebot.bot.feature.welders.WeldersFeature;
import com.warehousebot.bot.feature.reference.ReferenceModule;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.config.AppConfig;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.SchemaInitializer;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.repository.DirectoryRepository;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Точка входа и сборка приложения (composition root). */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config.properties"));

        Database db = new Database(config.dbUrl(), config.dbPoolSize());
        SchemaInitializer.initialize(db);

        WarehouseBot bot = new WarehouseBot(config.botToken(), config.botUsername());
        TelegramMessenger messenger = new TelegramMessenger(bot);

        UserRepository users = new UserRepository(db);
        ReturnRequestRepository returnRequests = new ReturnRequestRepository(db);
        UserService userService = new UserService(users, config.adminId());
        SessionStore sessions = new SessionStore();
        Ui ui = new Ui(messenger, users);
        MainMenu menu = new MainMenu(messenger, userService);
        AccessGate gate = new AccessGate(messenger, userService, users, returnRequests);
        EmailSender email = new EmailSender(config.emailLogin(), config.emailPassword(), config.emailTarget());
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });

        AppContext ctx = new AppContext(config, db, messenger, sessions, ui, menu, gate, users, userService,
                returnRequests, email, scheduler);

        List<Feature> features = createFeatures(ctx);

        Routes routes = new Routes();
        for (Feature f : features) {
            for (BotModule m : f.modules()) m.register(routes);
        }

        ChatExecutor executor = new ChatExecutor(config.workerThreads());
        bot.attach(new UpdateDispatcher(routes, sessions, messenger, gate, menu), executor);

        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        log.info("✅ Бот Склада успешно запущен и ждет сообщений!");

        features.forEach(Feature::start);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Остановка бота…");
            scheduler.shutdownNow();
            executor.close();
            db.close();
        }, "shutdown"));
    }

    /** Порядок важен: модули, зарегистрированные раньше, раньше получают общие стадии (например, FALLBACK). */
    public static List<Feature> createFeatures(AppContext ctx) {
        List<Feature> features = new ArrayList<>();
        features.add(() -> List.of(
                new CoreModule(ctx.messenger(), ctx.menu()),
                new ReferenceModule(ctx.messenger(), ctx.ui()),
                new DirectoryModule(ctx.messenger(), ctx.ui(), ctx.menu(), new DirectoryRepository(ctx.db()))));

        // Склад раньше инструмента: его «+услуга» должна проверяться до поиска инструмента (стадия FALLBACK).
        StockFeature stock = new StockFeature(ctx);
        ToolsFeature tools = new ToolsFeature(ctx, stock.facade());
        // ТМ раньше графика: при наборе «❌ Отменить» сначала закрывается отчёт по заявке.
        TmFeature tm = new TmFeature(ctx, stock.facade());
        ScheduleFeature schedule = new ScheduleFeature(ctx);
        features.add(stock);
        features.add(tools);
        features.add(tm);
        features.add(schedule);
        features.add(new WeldersFeature(ctx));
        features.add(new AdminFeature(ctx, stock.facade(), tools.facade(), schedule.facade()));
        features.add(() -> List.of(new InventoryModule(ctx.messenger(), stock.facade(), tools.facade())));
        return features;
    }
}
