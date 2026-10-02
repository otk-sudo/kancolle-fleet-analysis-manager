package io.github.otksudo.fleetanalysis.domain.lottery;

/**
 * 抽選の設定。
 *
 * @param enabled             抽選を使うか
 * @param lossBonusEnabled    落選補正を使うか
 * @param lossBonusStrength   落選1回あたりに加える当たりやすさ
 */
public record LotterySettings(boolean enabled, boolean lossBonusEnabled, double lossBonusStrength) {

    public static final LotterySettings DEFAULT = new LotterySettings(true, true, 1.0);

    public LotterySettings {
        if (lossBonusStrength < 0) {
            throw new IllegalArgumentException("lossBonusStrength must not be negative");
        }
    }

    /** 当たりやすさ = 1 + 最後の当選以降の落選回数 × 補正の強さ。補正オフなら常に1。 */
    public double weightFor(int lossesSinceLastWin) {
        if (lossesSinceLastWin < 0) {
            throw new IllegalArgumentException("lossesSinceLastWin must not be negative");
        }
        if (!lossBonusEnabled) {
            return 1.0;
        }
        return 1.0 + lossesSinceLastWin * lossBonusStrength;
    }
}
