package com.warehousebot.bot.feature.tools;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.repository.ToolRepository;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.ToolFacade;
import com.warehousebot.service.ToolService;

import java.util.List;

/** Функция «Инструмент»: панель МОЛ, быстрые команды /give_ и /take_, поиск и загрузка базы из Excel. */
public final class ToolsFeature implements Feature {

    private final ToolService service;
    private final ToolsModule module;

    public ToolsFeature(AppContext ctx, StockFacade stock) {
        this.service = new ToolService(ctx.db(), new ToolRepository(ctx.db()));
        this.module = new ToolsModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.users(), service, stock);
    }

    @Override
    public List<BotModule> modules() {
        return List.of(module);
    }

    /** Операции с инструментом для других функций (подотчёт, загрузки). */
    public ToolFacade facade() {
        return service;
    }
}
