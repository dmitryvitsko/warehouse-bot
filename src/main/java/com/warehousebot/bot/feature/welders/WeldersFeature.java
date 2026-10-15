package com.warehousebot.bot.feature.welders;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.repository.WelderRepository;
import com.warehousebot.service.WelderService;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Сварочные аппараты: панель, передачи между коллегами и бот-надзиратель конца смены. */
public final class WeldersFeature implements Feature {

    private static final long MONITOR_PERIOD_MINUTES = 5;

    private final AppContext ctx;
    private final WeldersModule module;
    private final WelderMonitor monitor;

    public WeldersFeature(AppContext ctx) {
        this.ctx = ctx;
        WelderService welders = new WelderService(ctx.db(), new WelderRepository(ctx.db()));
        WelderLocks locks = new WelderLocks();
        this.module = new WeldersModule(ctx.messenger(), ctx.ui(), ctx.users(), ctx.userService(), welders, locks);
        this.monitor = new WelderMonitor(welders, locks, ctx.messenger());
    }

    @Override
    public List<BotModule> modules() {
        return List.of(module);
    }

    @Override
    public void start() {
        ctx.scheduler().scheduleAtFixedRate(monitor, 0, MONITOR_PERIOD_MINUTES, TimeUnit.MINUTES);
    }
}
