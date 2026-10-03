package io.github.otksudo.fleetanalysis.domain.lottery;

import java.time.Instant;
import java.util.List;

/**
 * 抽選の記録（仕様 6.4）。後から公平性を確認できるよう、対象者・当たりやすさ・乱数の種をすべて残す。
 *
 * @param id         抽選ID
 * @param mode       抽選のしかた
 * @param executedAt 実行日時
 * @param seed       乱数の種。{@link WeightedLottery#draw} に同じ種と対象者を渡すと同じ結果になる
 * @param entries    対象者ごとの結果
 */
public record LotteryRecord(
        String id, LotteryMode mode, Instant executedAt, long seed, List<Entry> entries) {

    public LotteryRecord {
        entries = List.copyOf(entries);
    }

    /**
     * 対象者1人分の結果。
     *
     * @param applicationId 応募ID
     * @param weight        当たりやすさ
     * @param won           当選したか
     */
    public record Entry(String applicationId, double weight, boolean won) {
    }
}
