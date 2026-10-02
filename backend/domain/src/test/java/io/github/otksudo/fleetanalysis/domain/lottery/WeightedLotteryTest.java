package io.github.otksudo.fleetanalysis.domain.lottery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedLotteryTest {

    private static final List<LotteryEntry> ENTRIES = List.of(
            new LotteryEntry("a", 1.0),
            new LotteryEntry("b", 1.0),
            new LotteryEntry("c", 3.0),
            new LotteryEntry("d", 1.0));

    @Test
    void 同じ種なら同じ結果になる() {
        assertThat(WeightedLottery.draw(ENTRIES, 2, 42L)).isEqualTo(WeightedLottery.draw(ENTRIES, 2, 42L));
    }

    @Test
    void 同じ人が二度当たらない() {
        List<String> winners = WeightedLottery.draw(ENTRIES, 4, 7L);
        assertThat(winners).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    void 当選人数が対象者より多ければ全員当選() {
        assertThat(WeightedLottery.draw(ENTRIES, 10, 1L)).hasSize(4);
    }

    @Test
    void 対象者がいなければ当選者なし() {
        assertThat(WeightedLottery.draw(List.of(), 3, 1L)).isEmpty();
    }

    @Test
    void 重みが大きい人ほど当たりやすい() {
        Map<String, Integer> firstPlace = new HashMap<>();
        for (long seed = 0; seed < 10_000; seed++) {
            firstPlace.merge(WeightedLottery.draw(ENTRIES, 1, seed).getFirst(), 1, Integer::sum);
        }
        // c の重みは3/6なので、おおよそ半分で1位になる
        assertThat(firstPlace.get("c")).isBetween(4_500, 5_500);
        assertThat(firstPlace.get("a")).isBetween(1_400, 1_950);
    }
}
