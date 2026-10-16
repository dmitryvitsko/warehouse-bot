package com.warehousebot.bot.feature.stock;

import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.repository.BalanceRepository;
import com.warehousebot.repository.MaterialRepository;
import com.warehousebot.repository.TariffRepository;
import com.warehousebot.repository.TransactionRepository;
import com.warehousebot.service.ReceiptService;
import com.warehousebot.service.ReportService;
import com.warehousebot.service.ReturnService;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.StockService;
import com.warehousebot.service.TariffService;

import java.util.List;

/** Склад, подотчёт, списание/возврат, калькулятор квитанции, тарифы и отчёт по складу. */
public final class StockFeature implements Feature {

    private final StockService stock;
    private final List<BotModule> modules;

    public StockFeature(AppContext ctx) {
        MaterialRepository materials = new MaterialRepository(ctx.db());
        BalanceRepository balances = new BalanceRepository(ctx.db());
        TransactionRepository transactions = new TransactionRepository(ctx.db());
        TariffRepository tariffRepository = new TariffRepository(ctx.db());

        this.stock = new StockService(ctx.db(), materials, balances, transactions);
        ReturnService returns = new ReturnService(ctx.db(), ctx.returnRequests(), balances, materials, transactions);
        TariffService tariffs = new TariffService(tariffRepository);
        ReceiptService receipts = new ReceiptService(tariffs, balances);
        ReportService reports = new ReportService(transactions, materials, balances);

        this.modules = List.of(
                new WarehouseModule(ctx.messenger(), ctx.ui(), ctx.menu(),ctx.users(), stock, reports, ctx.email()),
                new WriteOffModule(ctx.messenger(), ctx.ui(), ctx.menu(), ctx.users(), stock, returns),
                new ReceiptModule(ctx.messenger(), ctx.ui(), ctx.menu(), receipts, tariffs));
    }

    @Override
    public List<BotModule> modules() {
        return modules;
    }

    /** Операции склада для других функций (ТМ, подотчёт, инструмент, загрузки). */
    public StockFacade facade() {
        return stock;
    }
}
