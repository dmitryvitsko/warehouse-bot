package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.domain.ReceiptLine;
import com.warehousebot.domain.WriteOffItem;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние диалогов склада. Ключи общие для модулей пакета: модули сбрасывают чужие состояния
 * так же, как это делала прежняя версия (например, выбор материала для списания отменяет ввод количества со склада).
 */
final class StockSessions {

    /** Открыта карточка материала на складе: число в чате — количество к выдаче. */
    static final SessionKey<WhTake> WAREHOUSE_TAKE = SessionKey.dialog("stock.warehouseTake");

    /** Материал, выбранный кнопкой TAKE_MAT из старого списка склада: ждём количество. */
    static final SessionKey<Integer> WAITING_TAKE_MATERIAL = SessionKey.dialog("stock.waitingTakeMaterial");

    /** Список материалов к списанию/возврату и шаг оформления. */
    static final SessionKey<WriteOffCart> WRITE_OFF_CART = SessionKey.dialog("stock.writeOffCart");

    /** Калькулятор квитанции: позиции сохраняются при отмене, сбрасывается только ожидание количества. */
    static final SessionKey<ReceiptCart> RECEIPT_CART = SessionKey.custom("stock.receiptCart", cart -> {
        cart.waitingServiceId = -1;
        cart.waitingMaterialId = -1;
        return cart;
    });

    private StockSessions() {
    }

    /** Карточка материала: какой материал, с какой страницы склада и какое сообщение редактировать. */
    record WhTake(int materialId, int page, int anchorMsgId) {
    }

    /** Корзина списания (бывший WriteOffCartSession). */
    static final class WriteOffCart {
        Integer anchorMsgId;
        String step = "";
        final List<CartItem> items = new ArrayList<>();
        /** Материал, для которого вводится количество. */
        PendingItem tempItem;

        boolean paidReceipt;
        String receiptNumber = "";
        String phoneNumber = "";
        String contractNumber = "";
        String address = "";
    }

    /** Материал, выбранный для списания: сколько ещё можно добавить в корзину. */
    record PendingItem(int materialId, String materialName, String unit, double priceWithVat, double maxAvailable) {

        CartItem withQuantity(double quantity) {
            return new CartItem(materialId, materialName, unit, priceWithVat, quantity);
        }
    }

    /** Позиция корзины списания. {@code materialName} — «Название [инв. №]», без экранирования. */
    record CartItem(int materialId, String materialName, String unit, double priceWithVat, double quantity) {

        WriteOffItem toWriteOff(boolean paid, String receiptNumber, String phone, String contract, String address,
                                String closingCode, String reason) {
            return new WriteOffItem(materialId, materialName, unit, priceWithVat, quantity, paid, receiptNumber,
                    phone, contract, address, closingCode, reason);
        }
    }

    /** Корзина калькулятора квитанции (бывший ReceiptSession). */
    static final class ReceiptCart {
        Integer anchorMsgId;
        final List<ReceiptLine> items = new ArrayList<>();
        int waitingServiceId = -1;
        int waitingMaterialId = -1;
    }

    /** Текст и клавиатура одного экрана. */
    record Screen(String html, InlineKeyboardMarkup markup) {
    }
}
