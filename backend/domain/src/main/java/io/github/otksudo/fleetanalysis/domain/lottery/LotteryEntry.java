package io.github.otksudo.fleetanalysis.domain.lottery;

import java.util.Objects;

/**
 * 抽選の対象者1人分。
 *
 * @param applicationId 応募ID
 * @param weight        当たりやすさ（0より大きい数）。{@link LotterySettings#weightFor(int)} で計算する
 */
public record LotteryEntry(String applicationId, double weight) {

    public LotteryEntry {
        Objects.requireNonNull(applicationId, "applicationId");
        // !(weight > 0) と書くと、0以下に加えて NaN（数でない値）も弾ける
        if (!(weight > 0)) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
    }
}
