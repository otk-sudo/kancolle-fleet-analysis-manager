package io.github.otksudo.fleetanalysis.domain.lottery;

/**
 * 抽選の設定（仕様 6.1、6.3）。配信者・運営が設定画面で変更できる。
 *
 * @param enabled           抽選を使うか。使わない場合は受付順＋手動の並べ替えだけで運用する
 * @param lossBonusEnabled  落選補正を使うか
 * @param lossBonusStrength 落選1回につき、当たりやすさにいくつ足すか（初期値1.0）
 */
public record LotterySettings(boolean enabled, boolean lossBonusEnabled, double lossBonusStrength) {

    /** 初期設定: 抽選あり、落選補正あり、落選1回につき+1。 */
    public static final LotterySettings DEFAULT = new LotterySettings(true, true, 1.0);

    public LotterySettings {
        if (lossBonusStrength < 0) {
            throw new IllegalArgumentException("lossBonusStrength must not be negative");
        }
    }

    /**
     * 応募者の「当たりやすさ」を計算する（仕様 6.3）。
     *
     * <p>当たりやすさ = 1 + 最後の当選以降の落選回数 × 補正の強さ。
     * 例: 補正の強さ1.0で2回落選している人は 1 + 2 × 1.0 = 3 となり、落選0回の人の3倍当たりやすい。
     * 当選すると落選回数は0に戻る（数えるのは「最後に当選してから」の回数のため）。
     *
     * @param lossesSinceLastWin 最後に当選してからの落選回数
     * @return 当たりやすさ（1以上）。補正オフなら常に1
     */
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
