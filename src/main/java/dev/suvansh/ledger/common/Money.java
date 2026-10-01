package dev.suvansh.ledger.common;

/**
 * An amount of money in minor units (cents). Signed, because postings are
 * outflows (negative) and inflows (positive) that must sum to zero.
 *
 * <p>Currency is deliberately not modelled yet; every amount is assumed to be
 * in the same currency.
 */
public record Money(long minorUnits) {

    public static final Money ZERO = new Money(0);

    public static Money ofMinorUnits(long minorUnits) {
        return new Money(minorUnits);
    }

    /** Throws {@link ArithmeticException} on long overflow instead of wrapping around. */
    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    /** Throws {@link ArithmeticException} on long overflow instead of wrapping around. */
    public Money minus(Money other) {
        return new Money(Math.subtractExact(minorUnits, other.minorUnits));
    }

    /** Throws {@link ArithmeticException} for {@code Long.MIN_VALUE}, which has no positive counterpart. */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits));
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }
}
