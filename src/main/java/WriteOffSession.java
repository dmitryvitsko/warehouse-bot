public class WriteOffSession {
    public String step;             // Текущий шаг диалога
    public int materialId;          // ID выбранного материала
    public String materialName;     // Название материала
    public String unit;             // Ед. измерения (м, шт)
    public double priceWithVat;     // Цена с НДС
    public double maxAvailable;     // Сколько всего на руках у мастера
    public double quantity;         // Сколько списывает
    public boolean isPaidReceipt;   // true = По квитанции, false = Без квитанции
    public String receiptNumber;    // Номер квитанции
    public String phoneNumber;      // Номер телефона, на который оформлена заявка
    public String contractNumber;   // Номер договора
    public String address;          // Адрес абонента
    public String reason;           // Причина (если без квитанции)
}