package io.github.otksudo.fleetanalysis.domain.lottery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link LotterySettings} のテスト（当たりやすさの計算）。 */
class LotterySettingsTest {

    @Test
    void 落選回数に応じて当たりやすくなる() {
        LotterySettings settings = LotterySettings.DEFAULT;
        assertThat(settings.weightFor(0)).isEqualTo(1.0);
        assertThat(settings.weightFor(3)).isEqualTo(4.0);
    }

    @Test
    void 補正の強さを変えられる() {
        assertThat(new LotterySettings(true, true, 0.5).weightFor(2)).isEqualTo(2.0);
    }

    @Test
    void 補正オフなら全員同じ当たりやすさ() {
        assertThat(new LotterySettings(true, false, 1.0).weightFor(5)).isEqualTo(1.0);
    }
}
