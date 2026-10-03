package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.HistoryEntry;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import io.github.otksudo.fleetanalysis.domain.application.SkipReason;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@link DynamoDbApplicationRepository} のテスト。DynamoDB Local（テスト用に同じプログラムの中で動く）を使う。
 *
 * <p>保存した値がすべて同じ形で読み戻せるか、と、二重登録を防げるかを確かめる。
 */
class DynamoDbApplicationRepositoryTest {

    private final ApplicationRepository repository = LocalDynamoDb.newStorage().applications();

    private static Application newApplication(String id, String submissionId, String xId, String receivedAt) {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("xId", xId);
        answers.put("admiralName", "提督" + id);
        answers.put("goal", "甲勲章");
        answers.put("purpose", List.of("イベント攻略", "戦果")); // チェックボックスの回答はリストになる
        return new Application(
                id, submissionId, XId.parse(xId), "提督" + id, false, "https://example.com/" + id, "2026-10",
                answers, Instant.parse(receivedAt),
                List.of(new Flag(FlagType.REAPPLY, "過去に応募があります（分析済み）", "old-1")));
    }

    @Test
    void 保存した応募を全項目そのまま読み戻せる() {
        Application application = newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z");
        Instant now = Instant.parse("2026-10-02T09:30:00.5Z");
        application.changeStatus(ApplicationStatus.SCHEDULED, null, now);
        application.changeStreamDate(LocalDate.parse("2026-10-10"), now);
        application.changeMemo("先に装備を確認", now);
        application.changeAnalysisMemo("制空値が足りない", now);
        application.changeArchiveUrl("https://example.com/archive?t=90", now);
        application.changePosition(12345);
        application.markWonLottery();
        repository.save(application);

        Application loaded = repository.findById("a1").orElseThrow();
        assertThat(loaded.xId()).isEqualTo(XId.parse("@alpha"));
        assertThat(loaded.admiralName()).isEqualTo("提督a1");
        assertThat(loaded.answers()).isEqualTo(application.answers());
        assertThat(loaded.flags()).isEqualTo(application.flags());
        assertThat(loaded.receivedAt()).isEqualTo(application.receivedAt());
        assertThat(loaded.status()).isEqualTo(ApplicationStatus.SCHEDULED);
        assertThat(loaded.streamDate()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(loaded.memo()).isEqualTo("先に装備を確認");
        assertThat(loaded.analysisMemo()).isEqualTo("制空値が足りない");
        assertThat(loaded.archiveUrl()).isEqualTo("https://example.com/archive?t=90");
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.position()).isEqualTo(12345);
        assertThat(loaded.wonLottery()).isTrue();
        assertThat(loaded.updatedAt()).isEqualTo(now);
        assertThat(loaded.statusChangedAt()).isEqualTo(now);
    }

    @Test
    void 回答の数は整数ならLong_小数や大きすぎる数ならDoubleで読み戻せる() {
        Application application = newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z");
        Map<String, Object> answers = new LinkedHashMap<>(application.answers());
        answers.put("count", 3L);
        answers.put("ratio", 1.5);
        answers.put("huge", 1e21);
        repository.save(new Application("a1", "s1", XId.parse("@Alpha"), "提督", false, "https://example.com/",
                "2026-10", answers, Instant.parse("2026-10-01T10:00:00Z"), List.of()));

        Map<String, Object> loaded = repository.findById("a1").orElseThrow().answers();
        assertThat(loaded.get("count")).isEqualTo(3L);
        assertThat(loaded.get("ratio")).isEqualTo(1.5);
        assertThat(loaded.get("huge")).isEqualTo(1e21);
    }

    @Test
    void 一覧は保存した最新の内容を返す() {
        Application application = newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z");
        repository.save(application);
        application.changeStatus(ApplicationStatus.SKIPPED, SkipReason.WITHDRAWN, Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(application);
        assertThat(repository.findAll()).extracting(Application::status).containsExactly(ApplicationStatus.SKIPPED);
        assertThat(repository.findAll()).extracting(Application::skipReason).containsExactly(SkipReason.WITHDRAWN);
        assertThat(repository.findByXId(XId.parse("@alpha"))).extracting(Application::status)
                .containsExactly(ApplicationStatus.SKIPPED);
    }

    @Test
    void 回答IDとXのIDで探せる() {
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));
        repository.save(newApplication("a2", "s2", "@alpha", "2026-10-01T11:00:00Z"));
        repository.save(newApplication("a3", "s3", "@Bravo", "2026-10-01T12:00:00Z"));

        assertThat(repository.findBySubmissionId("s2").orElseThrow().id()).isEqualTo("a2");
        assertThat(repository.findBySubmissionId("none")).isEmpty();
        assertThat(repository.findByXId(XId.parse("@ALPHA"))).extracting(Application::id).containsExactly("a1", "a2");
        assertThat(repository.findAll()).extracting(Application::id).containsExactly("a1", "a2", "a3");
    }

    @Test
    void 受付日時の秒の端数があってもなくても受付順に並ぶ() {
        // "10:00:00Z" と "10:00:00.5Z" は、そのまま文字列で並べると順番が逆になる（AttributeValues.sortableTime）
        repository.save(newApplication("late", "s1", "@a", "2026-10-01T10:00:00.5Z"));
        repository.save(newApplication("early", "s2", "@b", "2026-10-01T10:00:00Z"));
        assertThat(repository.findAll()).extracting(Application::id).containsExactly("early", "late");
    }

    @Test
    void 同じ回答IDを別の応募として保存しようとすると断る() {
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));

        assertThatThrownBy(() -> repository.save(newApplication("a2", "s1", "@Alpha", "2026-10-01T10:00:00Z")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findAll()).extracting(Application::id).containsExactly("a1");
    }

    @Test
    void 同じ応募の上書き保存はできる() {
        Application application = newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z");
        repository.save(application);
        application.changeMemo("メモ", Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(application);
        assertThat(repository.findById("a1").orElseThrow().memo()).isEqualTo("メモ");
    }

    @Test
    void XのIDで消すと回答IDの控えも消える() {
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));
        repository.save(newApplication("a2", "s2", "@Bravo", "2026-10-01T11:00:00Z"));

        Application withHistory = repository.findById("a1").orElseThrow();
        withHistory.changeStatus(ApplicationStatus.SKIPPED, SkipReason.WITHDRAWN, Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(withHistory);

        repository.deleteByXId(XId.parse("@alpha"));

        assertThat(repository.findAll()).extracting(Application::id).containsExactly("a2");
        assertThat(repository.findHistory("a1")).isEmpty();
        assertThat(repository.findBySubmissionId("s1")).isEmpty();
        // 控えが消えているので、同じ回答IDで新しく登録できる
        repository.save(newApplication("a3", "s1", "@Alpha", "2026-10-03T10:00:00Z"));
    }

    @Test
    void 業務ロジックと組み合わせて受付と重複の印が動く() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
        ApplicationService service = new ApplicationService(repository, clock);
        Application first = service.submit(intake("s1", "@Alpha", "2026-10-01T10:00:00Z"));
        Application again = service.submit(intake("s1", "@Alpha", "2026-10-01T10:00:00Z")); // 同じ回答の再送
        Application second = service.submit(intake("s2", "@alpha", "2026-10-01T11:00:00Z"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(second.flags()).extracting(Flag::type).containsExactly(FlagType.DUPLICATE);
        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void ほかの人が先に保存していたら古い版では保存できない() {
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));
        // 2人が同じ版（1）を読み込む
        Application first = repository.findById("a1").orElseThrow();
        Application second = repository.findById("a1").orElseThrow();

        first.changeMemo("1人目", Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(first);
        second.changeMemo("2人目", Instant.parse("2026-10-02T00:00:01Z"));

        assertThatThrownBy(() -> repository.save(second)).isInstanceOf(ConflictException.class);
        Application stored = repository.findById("a1").orElseThrow();
        assertThat(stored.memo()).isEqualTo("1人目");
        assertThat(stored.version()).isEqualTo(2);
    }

    @Test
    void まとめての保存は1件でも断られたらどれも保存しない() {
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));
        repository.save(newApplication("a2", "s2", "@Bravo", "2026-10-01T11:00:00Z"));
        Application ok = repository.findById("a1").orElseThrow();
        Application stale = repository.findById("a2").orElseThrow();
        Application other = repository.findById("a2").orElseThrow();
        other.changeMemo("先に保存", Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(other); // stale は古い版になる

        ok.changeMemo("まとめて", Instant.parse("2026-10-02T00:00:01Z"));
        stale.changeMemo("まとめて", Instant.parse("2026-10-02T00:00:01Z"));
        assertThatThrownBy(() -> repository.saveAll(List.of(ok, stale))).isInstanceOf(ConflictException.class);

        assertThat(repository.findById("a1").orElseThrow().memo()).isNull();
        assertThat(repository.findById("a2").orElseThrow().memo()).isEqualTo("先に保存");
    }

    @Test
    void 変更履歴を応募と一緒に保存し古い順に読み戻せる() {
        Application application = newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z");
        repository.save(application);
        application.changeStatus(ApplicationStatus.ANALYZING, null, Instant.parse("2026-10-02T10:00:00Z"));
        application.changeXId(XId.parse("@alpha2"), Instant.parse("2026-10-02T10:00:00Z"));
        repository.save(application);
        application.changeStatusBySystem(ApplicationStatus.DONE, null, Instant.parse("2026-10-02T11:00:00Z"), "次の人へ");
        repository.save(application);

        List<HistoryEntry> history = repository.findHistory("a1");
        // 同じ時刻の2件（ステータス → XのID）も、変えた順に並ぶ
        assertThat(history).extracting(HistoryEntry::kind)
                .containsExactly(HistoryEntry.Kind.STATUS, HistoryEntry.Kind.X_ID, HistoryEntry.Kind.STATUS);
        assertThat(history.get(2)).satisfies(entry -> {
            assertThat(entry.from()).isEqualTo("analyzing");
            assertThat(entry.to()).isEqualTo("done");
            assertThat(entry.note()).isEqualTo("次の人へ");
            assertThat(entry.at()).isEqualTo(Instant.parse("2026-10-02T11:00:00Z"));
        });
        // XのIDを直すと、索引（GSI3）も新しいIDで探せるようになる
        assertThat(repository.findByXId(XId.parse("@alpha2"))).extracting(Application::id).containsExactly("a1");
        assertThat(repository.findByXId(XId.parse("@alpha"))).isEmpty();
    }

    @Test
    void 版のない段階1の応募も読み込んで保存し直せる() {
        String table = LocalDynamoDb.newTable();
        ApplicationRepository repository = new DynamoDbApplicationRepository(LocalDynamoDb.client(), table);
        repository.save(newApplication("a1", "s1", "@Alpha", "2026-10-01T10:00:00Z"));
        // 段階1の保存の形（version の項目がない）にする
        LocalDynamoDb.client().updateItem(u -> u.tableName(table)
                .key(Map.of("PK", AttributeValue.fromS("APP#a1"), "SK", AttributeValue.fromS("META")))
                .updateExpression("REMOVE version"));

        Application legacy = repository.findById("a1").orElseThrow();
        assertThat(legacy.version()).isEqualTo(1);
        legacy.changeMemo("段階2で保存", Instant.parse("2026-10-02T00:00:00Z"));
        repository.save(legacy);
        assertThat(repository.findById("a1").orElseThrow().version()).isEqualTo(2);
    }

    private static IntakeCommand intake(String submissionId, String xId, String receivedAt) {
        return new IntakeCommand(submissionId, Instant.parse(receivedAt), "2026-10", Map.of(
                "xId", xId,
                "admiralName", "提督" + submissionId,
                "nameDisplay", "提督名でOK",
                "simulatorUrl", "https://example.com/" + submissionId));
    }
}
