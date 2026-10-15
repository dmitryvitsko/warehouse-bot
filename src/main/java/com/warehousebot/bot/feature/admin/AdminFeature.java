package com.warehousebot.bot.feature.admin;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.feature.orsh.OrshModule;
import com.warehousebot.repository.EmployeeRepository;
import com.warehousebot.repository.OrshRepository;
import com.warehousebot.service.AdminService;
import com.warehousebot.service.OrshService;
import com.warehousebot.service.ScheduleFacade;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.ToolFacade;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Администрирование, загрузки Excel, плановый осмотр ОРШ и ежедневная резервная копия базы. */
public final class AdminFeature implements Feature {

    private final AppContext ctx;
    private final List<BotModule> modules;

    public AdminFeature(AppContext ctx, StockFacade stock, ToolFacade tools, ScheduleFacade schedule) {
        this.ctx = ctx;
        AdminService admin = new AdminService(ctx.db(), ctx.users(), new EmployeeRepository(ctx.db()),
                ctx.returnRequests(), stock);
        OrshService orsh = new OrshService(new OrshRepository(ctx.db()));
        this.modules = List.of(
                new AdminModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.userService(), admin),
                new UploadsModule(ctx.messenger(), ctx.ui(), ctx.menu(), stock, tools, schedule, orsh),
                new OrshModule(ctx.messenger(), ctx.ui(), ctx.menu(), orsh, ctx.email()));
    }

    @Override
    public List<BotModule> modules() {
        return modules;
    }

    /** Резервная копия: первая через час после запуска, затем раз в сутки (как и раньше). */
    @Override
    public void start() {
        BackupJob backup = new BackupJob(ctx.db(), ctx.messenger(), ctx.userService().superAdminId());
        ctx.scheduler().scheduleAtFixedRate(backup, 1, 24, TimeUnit.HOURS);
    }
}
