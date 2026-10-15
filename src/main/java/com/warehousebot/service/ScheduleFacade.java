package com.warehousebot.service;

import java.io.File;

/** Операции с графиком, которые нужны другим функциям (загрузки). */
public interface ScheduleFacade {

    /**
     * Загрузка графика на указанный месяц; возвращает HTML-отчёт.
     * Ошибки разбора файла пробрасываются — загрузчик покажет «❌ Ошибка при скачивании/обработке файла: …».
     */
    String importSchedule(File file, int year, int month) throws Exception;
}
