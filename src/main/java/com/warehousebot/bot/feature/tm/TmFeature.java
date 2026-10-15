package com.warehousebot.bot.feature.tm;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.infrastructure.tm.TmClient;
import com.warehousebot.repository.TmAccountRepository;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.TmService;

import java.util.List;

/** Интеграция с «Мобильной ТМ»: заявки сотрудника и отчёт о закрытии со списанием материалов. */
public final class TmFeature implements Feature {

    private final TmModule tasks;
    private final TmReportModule report;

    public TmFeature(AppContext ctx, StockFacade stock) {
        TmClient client = new TmClient(ctx.config().tmApiUrl());
        TmService tm = new TmService(new TmAccountRepository(ctx.db()), client);
        this.tasks = new TmModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.userService(), tm);
        this.report = new TmReportModule(ctx.messenger(), tm, stock, tasks);
    }

    @Override
    public List<BotModule> modules() {
        return List.of(tasks, report);
    }
}
