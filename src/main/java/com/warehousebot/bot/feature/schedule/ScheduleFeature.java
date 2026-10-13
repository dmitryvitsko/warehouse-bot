package com.warehousebot.bot.feature.schedule;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.repository.ScheduleRepository;
import com.warehousebot.repository.SickLeaveRepository;
import com.warehousebot.service.ScheduleFacade;
import com.warehousebot.service.ScheduleService;
import com.warehousebot.service.SickLeaveService;

import java.util.List;

/** График работ и больничные в сборе. */
public final class ScheduleFeature implements Feature {

    private final ScheduleService scheduleService;
    private final List<BotModule> modules;

    public ScheduleFeature(AppContext ctx) {
        ScheduleRepository schedules = new ScheduleRepository(ctx.db());
        SickLeaveRepository sickLeaves = new SickLeaveRepository(ctx.db());
        this.scheduleService = new ScheduleService(ctx.db(), schedules);
        SickLeaveService sickLeaveService = new SickLeaveService(ctx.db(), schedules, sickLeaves, ctx.users());

        ScheduleModule scheduleModule = new ScheduleModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.users(),
                ctx.userService(), scheduleService);
        SickLeaveModule sickLeaveModule = new SickLeaveModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.users(),
                sickLeaveService, scheduleModule);
        this.modules = List.of(scheduleModule, sickLeaveModule);
    }

    @Override
    public List<BotModule> modules() {
        return modules;
    }

    /** Загрузка графика из Excel — для раздела загрузок администратора. */
    public ScheduleFacade facade() {
        return scheduleService;
    }
}
