package io.github.otksudo.fleetanalysis.infra.sqlite;

import static io.github.otksudo.fleetanalysis.infra.sqlite.SqliteValues.bool;
import static io.github.otksudo.fleetanalysis.infra.sqlite.SqliteValues.time;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.HistoryEntry;
import io.github.otksudo.fleetanalysis.domain.application.SkipReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 応募を SQLite に保存する {@link ApplicationRepository} の実装。
 *
 * <p>表は2つ使う（形は {@code db/migration/V1__create_tables.sql}）。
 * <ul>
 *   <li>{@code applications}: 応募1件につき1行
 *   <li>{@code application_history}: 変更履歴1件につき1行。{@code application_id} でどの応募の履歴かを表す
 * </ul>
 *
 * <p>SQL の中の {@code :id} のような部分は「あとで値を入れる場所」。値は {@code param("id", ...)} で渡す。
 * 値を SQL の文字に直接つなげないので、値に SQL が紛れ込んでも命令として実行されない（SQL インジェクションを防ぐ）。
 */
class SqliteApplicationRepository implements ApplicationRepository {

    private static final String CONFLICT_MESSAGE =
            "ほかの人が先にこの応募を変更しました。画面を読み込み直してから、もう一度変更してください";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    SqliteApplicationRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * 応募をまとめて保存する。全部保存できるか、1件も保存しないか（トランザクション）。
     *
     * <p>トランザクションの中で例外が起きると、それまでの書き込みはすべて取り消される（ロールバック）。
     * SQLite は同時に1つしか書き込めないので、確かめてから書くまでの間に、ほかの書き込みが割り込むことはない
     * （{@link SqliteStorage} の TransactionMode.IMMEDIATE を参照）。
     *
     * <p>応募ごとに次のようにする。
     * <ul>
     *   <li>新しい応募（版0）: 同じ応募ID・同じ回答IDの行がないことを確かめてから、版1で追加する
     *   <li>保存済みの応募: 「版が読み込んだときと同じ行だけ」を書き換え、版を1つ増やす。
     *       書き換えた行が0なら、誰かが先に保存して版が進んでいるので断る（仕様 5.1 の同時変更の検知）
     *   <li>まだ保存していない変更履歴を追加する
     * </ul>
     */
    @Override
    public void saveAll(List<Application> applications) {
        transaction.executeWithoutResult(status -> {
            for (Application application : applications) {
                if (application.version() == 0) {
                    insert(application);
                } else {
                    update(application);
                }
                for (HistoryEntry entry : application.pendingHistory()) {
                    insertHistory(application.id(), entry);
                }
            }
        });
        // 全部保存できてから、版を進める（途中で断られたときは、どの応募の版も変えない）
        for (Application application : applications) {
            application.markSaved();
        }
    }

    private void insert(Application application) {
        if (exists("SELECT COUNT(*) FROM applications WHERE id = :value", application.id())) {
            throw new ConflictException(CONFLICT_MESSAGE);
        }
        // 表の UNIQUE でも防げるが、先に確かめて、画面にわかりやすい説明を出す
        if (exists("SELECT COUNT(*) FROM applications WHERE submission_id = :value", application.submissionId())) {
            throw new ConflictException("同じフォームの回答がすでに別の応募として登録されています");
        }
        withValues(jdbc.sql("""
                INSERT INTO applications (
                    id, submission_id, x_id, admiral_name, anonymous, simulator_url, form_version, answers,
                    received_at, flags, status, skip_reason, stream_date, memo, analysis_memo, archive_url,
                    position, won_lottery, updated_at, status_changed_at, version)
                VALUES (
                    :id, :submissionId, :xId, :admiralName, :anonymous, :simulatorUrl, :formVersion, :answers,
                    :receivedAt, :flags, :status, :skipReason, :streamDate, :memo, :analysisMemo, :archiveUrl,
                    :position, :wonLottery, :updatedAt, :statusChangedAt, 1)
                """), application)
                .update();
    }

    private void update(Application application) {
        // 受付時に決まる値（回答ID、提督名、受付日時など）は変わらないので書き換えない
        int updated = withValues(jdbc.sql("""
                UPDATE applications SET
                    x_id = :xId, answers = :answers, flags = :flags, status = :status, skip_reason = :skipReason,
                    stream_date = :streamDate, memo = :memo, analysis_memo = :analysisMemo, archive_url = :archiveUrl,
                    position = :position, won_lottery = :wonLottery, updated_at = :updatedAt,
                    status_changed_at = :statusChangedAt, version = version + 1
                WHERE id = :id AND version = :version
                """), application)
                .param("version", application.version())
                .update();
        if (updated == 0) {
            throw new ConflictException(CONFLICT_MESSAGE);
        }
    }

    private boolean exists(String countSql, String value) {
        return jdbc.sql(countSql).param("value", value).query(Long.class).single() > 0;
    }

    /** INSERT と UPDATE で共通の値（:id など）を入れる */
    private static JdbcClient.StatementSpec withValues(JdbcClient.StatementSpec spec, Application application) {
        return spec
                .param("id", application.id())
                .param("submissionId", application.submissionId())
                .param("xId", application.xId().value())
                .param("admiralName", application.admiralName())
                .param("anonymous", bool(application.anonymous()))
                .param("simulatorUrl", application.simulatorUrl())
                .param("formVersion", application.formVersion())
                .param("answers", SqliteValues.toJson(application.answers()))
                .param("receivedAt", time(application.receivedAt()))
                .param("flags", SqliteValues.toJson(application.flags().stream().map(SqliteApplicationRepository::toFlagMap).toList()))
                .param("status", application.status().code())
                .param("skipReason", application.skipReason() == null ? null : application.skipReason().code())
                .param("streamDate", application.streamDate() == null ? null : application.streamDate().toString())
                .param("memo", application.memo())
                .param("analysisMemo", application.analysisMemo())
                .param("archiveUrl", application.archiveUrl())
                .param("position", application.position())
                .param("wonLottery", bool(application.wonLottery()))
                .param("updatedAt", time(application.updatedAt()))
                .param("statusChangedAt", time(application.statusChangedAt()));
    }

    private void insertHistory(String applicationId, HistoryEntry entry) {
        // seq（書き込んだ順の番号）は SQLite が自動でつける
        jdbc.sql("""
                INSERT INTO application_history (id, application_id, at, kind, from_value, to_value, note)
                VALUES (:id, :applicationId, :at, :kind, :from, :to, :note)
                """)
                .param("id", entry.id())
                .param("applicationId", applicationId)
                .param("at", time(entry.at()))
                .param("kind", entry.kind().code())
                .param("from", entry.from())
                .param("to", entry.to())
                .param("note", entry.note())
                .update();
    }

    @Override
    public Optional<Application> findById(String id) {
        return jdbc.sql("SELECT * FROM applications WHERE id = :id")
                .param("id", id)
                .query(SqliteApplicationRepository::fromRow)
                .optional();
    }

    @Override
    public Optional<Application> findBySubmissionId(String submissionId) {
        return jdbc.sql("SELECT * FROM applications WHERE submission_id = :submissionId")
                .param("submissionId", submissionId)
                .query(SqliteApplicationRepository::fromRow)
                .optional();
    }

    /** すべての応募を受付順に返す（約束では順番は決まっていないが、いつも同じ順のほうが画面や試験で扱いやすい） */
    @Override
    public List<Application> findAll() {
        return jdbc.sql("SELECT * FROM applications ORDER BY received_at, id")
                .query(SqliteApplicationRepository::fromRow)
                .list();
    }

    @Override
    public List<Application> findByXId(XId xId) {
        return jdbc.sql("SELECT * FROM applications WHERE x_id = :xId ORDER BY received_at, id")
                .param("xId", xId.value())
                .query(SqliteApplicationRepository::fromRow)
                .list();
    }

    /** 変更履歴を古い順（書き込んだ順）に返す。1回の変更で2件できたとき（ステータスとXのID）も、変えた順に並ぶ */
    @Override
    public List<HistoryEntry> findHistory(String applicationId) {
        return jdbc.sql("SELECT * FROM application_history WHERE application_id = :applicationId ORDER BY seq")
                .param("applicationId", applicationId)
                .query((row, rowNumber) -> new HistoryEntry(
                        row.getString("id"),
                        time(row.getString("at")),
                        HistoryEntry.Kind.fromCode(row.getString("kind")),
                        row.getString("from_value"),
                        row.getString("to_value"),
                        row.getString("note")))
                .list();
    }

    /**
     * 同じXのIDの応募を、変更履歴も含めて消す（仕様 8.1 削除依頼への対応）。
     *
     * <p>変更履歴には変更前のXのIDが残っていることがあるので、一緒に消す。
     * 履歴と応募を1つのトランザクションで消すので、片方だけ残ることはない。
     *
     * <p>TODO(段階9): 抽選記録の中の応募IDの置き換え（「削除済み」にする）も行う。
     */
    @Override
    public void deleteByXId(XId xId) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    DELETE FROM application_history
                    WHERE application_id IN (SELECT id FROM applications WHERE x_id = :xId)
                    """)
                    .param("xId", xId.value())
                    .update();
            jdbc.sql("DELETE FROM applications WHERE x_id = :xId")
                    .param("xId", xId.value())
                    .update();
        });
    }

    // ---- 表の1行と Java の Application の変換 ----

    /**
     * 表の1行を Application に戻す。
     *
     * @param row       読み込んだ行（列の名前で値を取り出せる）
     * @param rowNumber 何行目か（ここでは使わない）
     */
    private static Application fromRow(ResultSet row, int rowNumber) throws SQLException {
        List<Flag> flags = new ArrayList<>();
        for (Map<String, Object> flag : SqliteValues.jsonToList(row.getString("flags"))) {
            flags.add(new Flag(
                    FlagType.fromCode((String) flag.get("type")),
                    (String) flag.get("reason"),
                    (String) flag.get("relatedApplicationId")));
        }
        String skipReason = row.getString("skip_reason");
        String streamDate = row.getString("stream_date");
        return Application.restore(
                row.getString("id"),
                row.getString("submission_id"),
                XId.parse(row.getString("x_id")),
                row.getString("admiral_name"),
                row.getInt("anonymous") == 1,
                row.getString("simulator_url"),
                row.getString("form_version"),
                SqliteValues.jsonToMap(row.getString("answers")),
                time(row.getString("received_at")),
                flags,
                new Application.State(
                        ApplicationStatus.fromCode(row.getString("status")),
                        skipReason == null ? null : SkipReason.fromCode(skipReason),
                        streamDate == null ? null : LocalDate.parse(streamDate),
                        row.getString("memo"),
                        row.getString("analysis_memo"),
                        row.getString("archive_url"),
                        row.getLong("position"),
                        row.getInt("won_lottery") == 1,
                        time(row.getString("updated_at")),
                        time(row.getString("status_changed_at")),
                        row.getLong("version")));
    }

    /** 印を JSON にするための Map にする。関係する応募がないときは、その項目を入れない */
    private static Map<String, Object> toFlagMap(Flag flag) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", flag.type().code());
        value.put("reason", flag.reason());
        if (flag.relatedApplicationId() != null) {
            value.put("relatedApplicationId", flag.relatedApplicationId());
        }
        return value;
    }
}
