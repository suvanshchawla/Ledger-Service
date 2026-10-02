package dev.suvansh.ledger.common;

/** The API's JSON shape for an amount: {@code {"value": 2500, "currency": "CAD"}}. */
public record MoneyDto(long value, String currency) {

    public static MoneyDto of(Money money, String currency) {
        return new MoneyDto(money.minorUnits(), currency);
    }
}
