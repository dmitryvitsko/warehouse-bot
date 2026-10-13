package com.warehousebot.service;

import java.io.File;

/** Операции с инструментом, которые нужны другим функциям (подотчёт, загрузки). */
public interface ToolFacade {

    /** HTML-текст «Ваш закрепленный инструмент» (как прежний getUserToolsText). */
    String userToolsText(long userId);

    /** Загрузка базы инструмента из Excel; возвращает HTML-отчёт. */
    String importTools(File file);
}
