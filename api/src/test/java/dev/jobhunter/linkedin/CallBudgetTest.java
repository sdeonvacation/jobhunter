package dev.jobhunter.linkedin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallBudgetTest {

    @Test
    @DisplayName("trySpend returns true until the budget is exhausted, then false")
    void trySpendReturnsTrueUntilMaxThenFalse() {
        CallBudget budget = new CallBudget(3);

        assertThat(budget.trySpend()).isTrue();
        assertThat(budget.trySpend()).isTrue();
        assertThat(budget.trySpend()).isTrue();
        assertThat(budget.trySpend()).isFalse();
        assertThat(budget.trySpend()).isFalse();
    }

    @Test
    @DisplayName("used() counts every spend attempt including rejected ones")
    void usedCountsSpendAttempts() {
        CallBudget budget = new CallBudget(2);

        budget.trySpend();
        assertThat(budget.used()).isEqualTo(1);

        budget.trySpend();
        assertThat(budget.used()).isEqualTo(2);

        budget.trySpend(); // rejected — budget exhausted
        assertThat(budget.used()).isEqualTo(3);
    }

    @Test
    @DisplayName("exhausted() becomes true once the max call count is reached")
    void exhaustedAtMax() {
        CallBudget budget = new CallBudget(2);

        assertThat(budget.exhausted()).isFalse();

        budget.trySpend();
        assertThat(budget.exhausted()).isFalse();

        budget.trySpend();
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("Zero-max budget is immediately exhausted and rejects all spends")
    void zeroMaxBudgetRejectsAllSpends() {
        CallBudget budget = new CallBudget(0);

        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.trySpend()).isFalse();
    }
}