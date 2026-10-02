package io.github.otksudo.fleetanalysis.domain.lottery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link LotteryEntry} のテスト。 */
class LotteryEntryTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void 当たりやすさは0より大きい有限の数でなければならない(double weight) {
        assertThatThrownBy(() -> new LotteryEntry("a", weight)).isInstanceOf(IllegalArgumentException.class);
    }
}
