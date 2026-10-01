package dev.suvansh.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void plusAddsMinorUnits() {
        assertThat(Money.ofMinorUnits(150).plus(Money.ofMinorUnits(275)))
                .isEqualTo(Money.ofMinorUnits(425));
    }

    @Test
    void minusSubtractsMinorUnitsAndCanGoNegative() {
        assertThat(Money.ofMinorUnits(100).minus(Money.ofMinorUnits(250)))
                .isEqualTo(Money.ofMinorUnits(-150));
    }

    @Test
    void negateFlipsSign() {
        assertThat(Money.ofMinorUnits(500).negate()).isEqualTo(Money.ofMinorUnits(-500));
        assertThat(Money.ofMinorUnits(-500).negate()).isEqualTo(Money.ofMinorUnits(500));
    }

    @Test
    void amountAndItsNegationSumToZero() {
        Money amount = Money.ofMinorUnits(12_345);

        assertThat(amount.plus(amount.negate())).isEqualTo(Money.ZERO);
    }

    @Test
    void signPredicates() {
        assertThat(Money.ofMinorUnits(-1).isNegative()).isTrue();
        assertThat(Money.ZERO.isNegative()).isFalse();
        assertThat(Money.ofMinorUnits(1).isNegative()).isFalse();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ofMinorUnits(1).isZero()).isFalse();
    }

    @Test
    void equalityIsByValue() {
        assertThat(Money.ofMinorUnits(100)).isEqualTo(Money.ofMinorUnits(100));
        assertThat(Money.ofMinorUnits(100)).isNotEqualTo(Money.ofMinorUnits(101));
    }

    @Test
    void plusOverflowThrowsInsteadOfWrapping() {
        Money max = Money.ofMinorUnits(Long.MAX_VALUE);

        assertThatThrownBy(() -> max.plus(Money.ofMinorUnits(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void minusOverflowThrowsInsteadOfWrapping() {
        Money min = Money.ofMinorUnits(Long.MIN_VALUE);

        assertThatThrownBy(() -> min.minus(Money.ofMinorUnits(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negatingLongMinValueThrows() {
        assertThatThrownBy(() -> Money.ofMinorUnits(Long.MIN_VALUE).negate())
                .isInstanceOf(ArithmeticException.class);
    }
}
