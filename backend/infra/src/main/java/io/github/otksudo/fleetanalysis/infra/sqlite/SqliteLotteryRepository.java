package io.github.otksudo.fleetanalysis.infra.sqlite;

import static io.github.otksudo.fleetanalysis.infra.sqlite.SqliteValues.time;

import io.github.otksudo.fleetanalysis.domain.lottery.LotteryMode;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 抽選記録と抽選設定を SQLite に保存する {@link LotteryRepository} の実装。
 *
 * <ul>
 *   <li>抽選記録: {@code lotteries} 表に1回の抽選につき1行。対象者ごとの結果（entries）は JSON の文字で1つの列に入れる
 *   <li>抽選設定: {@code settings} 表の {@code kind = 'lottery'} の行。版（version）で同時の変更を見分ける
 * </ul>
 */
class SqliteLotteryRepository implements LotteryRepository {

    /** settings 表で、抽選設定の行を表す kind の値 */
    private static final String LOTTERY_SETTINGS = "lottery";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    SqliteLotteryRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void save(LotteryRecord record) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LotteryRecord.Entry entry : record.entries()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("applicationId", entry.applicationId());
            value.put("weight", entry.weight());
            value.put("won", entry.won());
            entries.add(value);
        }
        jdbc.sql("""
                INSERT INTO lotteries (id, mode, executed_at, seed, entries)
                VALUES (:id, :mode, :executedAt, :seed, :entries)
                """)
                .param("id", record.id())
                .param("mode", record.mode().code())
                .param("executedAt", time(record.executedAt()))
                .param("seed", record.seed())
                .param("entries", SqliteValues.toJson(entries))
                .update();
    }

    /** すべての抽選記録を新しい順に返す。実行日時が同じなら抽選IDの大きい順（いつも同じ順になるように） */
    @Override
    public List<LotteryRecord> findAll() {
        return jdbc.sql("SELECT * FROM lotteries ORDER BY executed_at DESC, id DESC")
                .query((row, rowNumber) -> {
                    List<LotteryRecord.Entry> entries = new ArrayList<>();
                    for (Map<String, Object> entry : SqliteValues.jsonToList(row.getString("entries"))) {
                        entries.add(new LotteryRecord.Entry(
                                (String) entry.get("applicationId"),
                                // JSON の数は、書き方によって Long か Double で読まれるので、Number として受け取って小数にする
                                ((Number) entry.get("weight")).doubleValue(),
                                (Boolean) entry.get("won")));
                    }
                    return new LotteryRecord(
                            row.getString("id"),
                            LotteryMode.fromCode(row.getString("mode")),
                            time(row.getString("executed_at")),
                            row.getLong("seed"),
                            entries);
                })
                .list();
    }

    @Override
    public VersionedSettings loadSettings() {
        // まだ一度も保存していなければ初期設定。版は1から始める
        return findSettings().orElse(new VersionedSettings(LotterySettings.DEFAULT, 1));
    }

    private Optional<VersionedSettings> findSettings() {
        return jdbc.sql("SELECT value, version FROM settings WHERE kind = :kind")
                .param("kind", LOTTERY_SETTINGS)
                .query((row, rowNumber) -> {
                    Map<String, Object> value = SqliteValues.jsonToMap(row.getString("value"));
                    LotterySettings settings = new LotterySettings(
                            (Boolean) value.get("enabled"),
                            (Boolean) value.get("lossBonusEnabled"),
                            ((Number) value.get("lossBonusStrength")).doubleValue());
                    return new VersionedSettings(settings, row.getInt("version"));
                })
                .optional();
    }

    /**
     * 版が読み込んだときと同じときだけ保存する。
     *
     * <p>「今の版を読む」と「書く」を1つのトランザクションにまとめるので、その間にほかの保存が割り込むことはない。
     * {@code ON CONFLICT(kind) DO UPDATE} は「行がなければ追加し、あれば書き換える」という SQLite の書き方
     * （公式: https://www.sqlite.org/lang_upsert.html ）。
     */
    @Override
    public VersionedSettings saveSettings(LotterySettings settings, int expectedVersion) {
        return transaction.execute(status -> {
            if (loadSettings().version() != expectedVersion) {
                return null; // 誰かが先に変更していた
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("enabled", settings.enabled());
            value.put("lossBonusEnabled", settings.lossBonusEnabled());
            value.put("lossBonusStrength", settings.lossBonusStrength());
            int nextVersion = expectedVersion + 1;
            jdbc.sql("""
                    INSERT INTO settings (kind, value, version) VALUES (:kind, :value, :version)
                    ON CONFLICT(kind) DO UPDATE SET value = excluded.value, version = excluded.version
                    """)
                    .param("kind", LOTTERY_SETTINGS)
                    .param("value", SqliteValues.toJson(value))
                    .param("version", nextVersion)
                    .update();
            return new VersionedSettings(settings, nextVersion);
        });
    }
}
