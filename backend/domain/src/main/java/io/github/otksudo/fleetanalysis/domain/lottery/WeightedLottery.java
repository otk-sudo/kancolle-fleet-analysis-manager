package io.github.otksudo.fleetanalysis.domain.lottery;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 重み付きの非復元抽出。同じ種と対象者なら同じ結果になるため、抽選記録から結果を再現できる。
 */
public final class WeightedLottery {

    private WeightedLottery() {
    }

    /**
     * @param entries 対象者（順番も結果に影響するため、記録と同じ順で渡す）
     * @param winners 当選人数。対象者より多い場合は全員当選
     * @param seed    乱数の種
     * @return 当選者の応募ID（当選した順）
     */
    public static List<String> draw(List<LotteryEntry> entries, int winners, long seed) {
        if (winners < 0) {
            throw new IllegalArgumentException("winners must not be negative");
        }
        // java.util.Randomは近い種どうしで最初の乱数が似通うため、種をよく混ぜるSplittableRandomを使う
        SplittableRandom random = new SplittableRandom(seed);
        List<LotteryEntry> remaining = new ArrayList<>(entries);
        List<String> result = new ArrayList<>();
        while (result.size() < winners && !remaining.isEmpty()) {
            double total = remaining.stream().mapToDouble(LotteryEntry::weight).sum();
            double point = random.nextDouble() * total;
            int picked = remaining.size() - 1;
            for (int i = 0; i < remaining.size(); i++) {
                point -= remaining.get(i).weight();
                if (point < 0) {
                    picked = i;
                    break;
                }
            }
            result.add(remaining.remove(picked).applicationId());
        }
        return result;
    }
}
