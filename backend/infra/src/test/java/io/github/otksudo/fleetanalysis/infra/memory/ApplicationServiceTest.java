package io.github.otksudo.fleetanalysis.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationChanges;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationFilter;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService.VersionedId;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.HistoryEntry;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import io.github.otksudo.fleetanalysis.domain.application.SkipReason;
import io.github.otksudo.fleetanalysis.domain.application.StreamView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * {@link ApplicationService}（業務ロジック）を、メモリ保存の実装と組み合わせて確かめるテスト。
 *
 * <p>時計は {@link Clock#fixed} で固定し、実行するたびに結果が変わらないようにしている。
 */
class ApplicationServiceTest {

    private static final String ACTOR = "テスト担当";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApplicationRepository repository = new InMemoryApplicationRepository();
    private final ApplicationService service = new ApplicationService(repository, clock);

    private Application submit(String submissionId, String xId, String receivedAt) {
        return service.submit(new IntakeCommand(
                submissionId,
                Instant.parse(receivedAt),
                "2026-10",
                Map.of(
                        "xId", xId,
                        "admiralName", "提督" + submissionId,
                        "nameDisplay", "提督名でOK",
                        "simulatorUrl", "https://example.com/" + submissionId,
                        "monthlySpending", "0円")));
    }

    /** 今の版を読み込んでから変える（画面で読み込んでから保存するのと同じ） */
    private Application change(String id, ApplicationChanges changes) {
        return service.update(id, service.get(id).version(), changes, ACTOR);
    }

    private Application changeStatus(String id, ApplicationStatus status) {
        return change(id, ApplicationChanges.none().withStatus(status));
    }

    private Application skip(String id, SkipReason reason) {
        return change(id, ApplicationChanges.none().withStatus(ApplicationStatus.SKIPPED).withSkipReason(reason));
    }

    private List<FlagType> flagsOf(String id) {
        return service.get(id).flags().stream().map(Flag::type).toList();
    }

    private List<String> queue() {
        return service.list(ApplicationFilter.all(), "queue").stream().map(Application::id).toList();
    }

    // ---- 受付 ----

    @Test
    void 同じ回答が2回届いても1件だけ登録する() {
        Application first = submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        Application second = submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void 同じ回答が同時に何度届いても1件だけ登録する() throws Exception {
        // 8つのスレッド（同時に動く処理）から、同じ回答IDの応募を一斉に送る
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Application>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(executor.submit(() -> {
                start.await(); // 全員そろってから同時に始める
                return submit("same", "@a", "2026-10-01T10:00:00Z");
            }));
        }
        start.countDown();
        for (Future<Application> result : results) {
            result.get();
        }
        executor.shutdown();

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void 項目コードが空の回答は入力の誤りとして断る() {
        Map<String, Object> answers = new java.util.HashMap<>(Map.of(
                "xId", "@Alpha", "admiralName", "提督", "simulatorUrl", "https://example.com/x"));
        answers.put("", "値");
        assertThatThrownBy(() -> service.submit(
                new IntakeCommand("s1", Instant.parse("2026-10-01T10:00:00Z"), "2026-10", answers)))
                .isInstanceOf(InvalidValueException.class);
        assertThat(repository.findAll()).isEmpty();
    }

    // ---- 重複・再応募の印（仕様 5.2） ----

    @Test
    void 未完了の応募がある人の応募には重複の印がつく() {
        submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        Application second = submit("s2", "alpha", "2026-10-01T11:00:00Z");
        assertThat(second.flags()).extracting(Flag::type).containsExactly(FlagType.DUPLICATE);
    }

    @Test
    void 過去に分析済みの人の応募には再応募の印がつく() {
        Application first = submit("s1", "alpha", "2026-10-01T10:00:00Z");
        changeStatus(first.id(), ApplicationStatus.ANALYZING);
        changeStatus(first.id(), ApplicationStatus.DONE);

        Application second = submit("s2", "alpha", "2026-10-02T10:00:00Z");
        assertThat(second.flags()).extracting(Flag::type).containsExactly(FlagType.REAPPLY);
    }

    @Test
    void 古い応募を見送りにすると新しい応募の重複は再応募に付け直される() {
        Application old = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application next = submit("s2", "@a", "2026-10-01T11:00:00Z");
        assertThat(flagsOf(next.id())).containsExactly(FlagType.DUPLICATE);

        skip(old.id(), SkipReason.WITHDRAWN);

        assertThat(flagsOf(next.id())).containsExactly(FlagType.REAPPLY);
        // 古いほうには印はつかない（自分より前の応募がないため）
        assertThat(flagsOf(old.id())).isEmpty();
    }

    @Test
    void 古い回答があとから届いたときも印は受付順で決まる() {
        // Apps Script の再送では、先に回答した人の応募があとから届くことがある
        Application later = submit("s2", "@a", "2026-10-01T11:00:00Z");
        Application earlier = submit("s1", "@a", "2026-10-01T10:00:00Z");

        assertThat(flagsOf(earlier.id())).isEmpty();
        assertThat(flagsOf(later.id())).containsExactly(FlagType.DUPLICATE);
    }

    // ---- ステータスの変更（仕様 5.1） ----

    @Test
    void 手では落選にできず落選から戻すこともできない() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        assertThatThrownBy(() -> changeStatus(application.id(), ApplicationStatus.LOST))
                .isInstanceOf(ConflictException.class);

        // 抽選で落選になった応募は、手では未着手に戻せない（抽選の取り消しでだけ戻る）
        Application lost = service.get(application.id());
        lost.changeStatusBySystem(ApplicationStatus.LOST, null, clock.instant(), ACTOR, "抽選で落選");
        repository.save(lost);
        assertThatThrownBy(() -> changeStatus(application.id(), ApplicationStatus.PENDING))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 見送りにするときは理由が必要で理由は履歴に残る() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        assertThatThrownBy(() -> changeStatus(application.id(), ApplicationStatus.SKIPPED))
                .isInstanceOf(InvalidValueException.class);

        skip(application.id(), SkipReason.WITHDRAWN);

        assertThat(service.get(application.id()).skipReason()).isEqualTo(SkipReason.WITHDRAWN);
        List<HistoryEntry> history = service.history(application.id());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).kind()).isEqualTo(HistoryEntry.Kind.STATUS);
        assertThat(history.get(0).from()).isEqualTo("pending");
        assertThat(history.get(0).to()).isEqualTo("skipped");
        assertThat(history.get(0).actor()).isEqualTo(ACTOR);
        assertThat(history.get(0).note()).contains("本人の取り下げ");

        // 見送りから戻すと、理由は消える
        changeStatus(application.id(), ApplicationStatus.PENDING);
        assertThat(service.get(application.id()).skipReason()).isNull();
    }

    @Test
    void ほかの人が先に変更していたら古い版での変更は断る() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        long version = service.get(application.id()).version();

        // 1人目が保存する（版が進む）
        service.update(application.id(), version, ApplicationChanges.none().withMemo("1人目"), ACTOR);

        // 2人目は古い版のまま保存しようとする → 断られ、1人目のメモは消えない
        assertThatThrownBy(() -> service.update(
                application.id(), version, ApplicationChanges.none().withMemo("2人目"), ACTOR))
                .isInstanceOf(ConflictException.class);
        assertThat(service.get(application.id()).memo()).isEqualTo("1人目");
    }

    @Test
    void 配信日を消せる() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        change(application.id(), ApplicationChanges.none().withStreamDate(LocalDate.parse("2026-10-10")));
        assertThat(service.get(application.id()).streamDate()).isEqualTo(LocalDate.parse("2026-10-10"));

        change(application.id(), ApplicationChanges.none().withClearStreamDate());
        assertThat(service.get(application.id()).streamDate()).isNull();
    }

    @Test
    void 分析メモとアーカイブURLを残せて正しくないURLは断る() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        change(application.id(), ApplicationChanges.none()
                .withAnalysisMemo("基地航空隊を見直す")
                .withArchiveUrl("https://www.youtube.com/watch?v=x&t=120"));
        Application saved = service.get(application.id());
        assertThat(saved.analysisMemo()).isEqualTo("基地航空隊を見直す");
        assertThat(saved.archiveUrl()).isEqualTo("https://www.youtube.com/watch?v=x&t=120");

        assertThatThrownBy(() -> change(application.id(), ApplicationChanges.none().withArchiveUrl("javascript:alert(1)")))
                .isInstanceOf(InvalidValueException.class);

        // 空にすると消える
        change(application.id(), ApplicationChanges.none().withArchiveUrl(""));
        assertThat(service.get(application.id()).archiveUrl()).isNull();
    }

    // ---- XのIDの修正（仕様 5.6） ----

    @Test
    void XのIDを直すと印と同じ人の応募が付け直され変更前のIDが履歴に残る() {
        Application right = submit("s1", "@teitoku", "2026-10-01T10:00:00Z");
        Application typo = submit("s2", "@teitok", "2026-10-01T11:00:00Z");
        assertThat(flagsOf(typo.id())).isEmpty();

        change(typo.id(), ApplicationChanges.none().withXId("@Teitoku"));

        Application fixed = service.get(typo.id());
        assertThat(fixed.xId()).isEqualTo(new XId("teitoku"));
        assertThat(fixed.answers()).containsEntry("xId", "teitoku");
        assertThat(flagsOf(typo.id())).containsExactly(FlagType.DUPLICATE);
        assertThat(service.sameApplicant(new XId("teitoku"))).extracting(Application::id)
                .containsExactly(typo.id(), right.id());
        assertThat(service.history(typo.id())).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(HistoryEntry.Kind.X_ID);
            assertThat(entry.from()).isEqualTo("teitok");
            assertThat(entry.to()).isEqualTo("teitoku");
        });
    }

    // ---- 重複の解消（仕様 5.2） ----

    @Test
    void この応募を残すと同じ人のほかの応募が再送のための見送りになる() {
        Application old = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application resent = submit("s2", "@a", "2026-10-01T11:00:00Z");

        service.keep(resent.id(), service.get(resent.id()).version(), ACTOR);

        Application skipped = service.get(old.id());
        assertThat(skipped.status()).isEqualTo(ApplicationStatus.SKIPPED);
        assertThat(skipped.skipReason()).isEqualTo(SkipReason.RESUBMITTED);
        assertThat(service.get(resent.id()).status()).isEqualTo(ApplicationStatus.PENDING);
        // 古いほうが見送りになったので、残した応募の印は「重複」から「再応募」になる
        assertThat(flagsOf(resent.id())).containsExactly(FlagType.REAPPLY);
    }

    @Test
    void 見送りにする中に分析予定があれば残す応募がステータスと並び順と当選を引き継ぐ() {
        Application other = submit("s0", "@b", "2026-10-01T09:00:00Z");
        Application won = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application resent = submit("s2", "@a", "2026-10-01T11:00:00Z");
        changeStatus(won.id(), ApplicationStatus.SCHEDULED);
        // 抽選で当選したことにする
        Application winner = service.get(won.id());
        winner.markWonLottery();
        repository.save(winner);
        changeStatus(other.id(), ApplicationStatus.SCHEDULED); // 当選した人の後ろに並ぶ
        long inheritedPosition = service.get(won.id()).position();

        Application kept = service.keep(resent.id(), service.get(resent.id()).version(), ACTOR);

        assertThat(kept.status()).isEqualTo(ApplicationStatus.SCHEDULED);
        assertThat(kept.position()).isEqualTo(inheritedPosition);
        assertThat(kept.wonLottery()).isTrue();
        assertThat(service.list(ApplicationFilter.statuses(ApplicationStatus.SCHEDULED), "queue"))
                .extracting(Application::id).containsExactly(resent.id(), other.id());
        assertThat(service.history(resent.id())).extracting(HistoryEntry::note)
                .containsExactly("重複の解消（分析予定を引き継ぎ）");
    }

    @Test
    void 分析予定の応募を残すとほかの未着手の応募だけが見送りになり並び順は変わらない() {
        Application old = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application scheduled = submit("s2", "@a", "2026-10-01T11:00:00Z");
        changeStatus(scheduled.id(), ApplicationStatus.SCHEDULED);
        long position = service.get(scheduled.id()).position();

        Application kept = service.keep(scheduled.id(), service.get(scheduled.id()).version(), ACTOR);

        assertThat(kept.status()).isEqualTo(ApplicationStatus.SCHEDULED);
        assertThat(kept.position()).isEqualTo(position);
        assertThat(service.get(old.id()).status()).isEqualTo(ApplicationStatus.SKIPPED);
    }

    @Test
    void 同じ人の応募に分析中があると重複の解消はできない() {
        Application analyzing = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application resent = submit("s2", "@a", "2026-10-01T11:00:00Z");
        changeStatus(analyzing.id(), ApplicationStatus.ANALYZING);

        assertThatThrownBy(() -> service.keep(resent.id(), service.get(resent.id()).version(), ACTOR))
                .isInstanceOf(ConflictException.class);
        assertThat(service.get(resent.id()).status()).isEqualTo(ApplicationStatus.PENDING);
    }

    // ---- まとめての変更・検索（仕様 5.6、7.1） ----

    @Test
    void まとめて見送りにできる() {
        Application a = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "@b", "2026-10-01T11:00:00Z");

        List<Application> updated = service.bulkChangeStatus(
                List.of(new VersionedId(a.id(), service.get(a.id()).version()),
                        new VersionedId(b.id(), service.get(b.id()).version())),
                ApplicationStatus.SKIPPED, SkipReason.INELIGIBLE, ACTOR);

        assertThat(updated).extracting(Application::id).containsExactly(a.id(), b.id());
        assertThat(service.get(a.id()).status()).isEqualTo(ApplicationStatus.SKIPPED);
        assertThat(service.get(b.id()).skipReason()).isEqualTo(SkipReason.INELIGIBLE);
    }

    @Test
    void まとめての変更は1件でもできなければどれも変えない() {
        Application pending = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application done = submit("s2", "@b", "2026-10-01T11:00:00Z");
        changeStatus(done.id(), ApplicationStatus.ANALYZING);
        changeStatus(done.id(), ApplicationStatus.DONE);

        // 分析済み → 分析予定 は表で許されていない
        assertThatThrownBy(() -> service.bulkChangeStatus(
                List.of(new VersionedId(pending.id(), service.get(pending.id()).version()),
                        new VersionedId(done.id(), service.get(done.id()).version())),
                ApplicationStatus.SCHEDULED, null, ACTOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("提督s2");
        assertThat(service.get(pending.id()).status()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void まとめての変更はほかの人が先に変えた応募があればどれも変えない() {
        Application a = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "@b", "2026-10-01T11:00:00Z");
        long staleVersionOfB = service.get(b.id()).version();
        change(b.id(), ApplicationChanges.none().withMemo("先に変更")); // b の版が進む

        assertThatThrownBy(() -> service.bulkChangeStatus(
                List.of(new VersionedId(a.id(), service.get(a.id()).version()), new VersionedId(b.id(), staleVersionOfB)),
                ApplicationStatus.SKIPPED, SkipReason.INELIGIBLE, ACTOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ほかの人が先に変更しました");
        assertThat(service.get(a.id()).status()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void まとめては分析中にできない() {
        Application a = submit("s1", "@a", "2026-10-01T10:00:00Z");
        assertThatThrownBy(() -> service.bulkChangeStatus(
                List.of(new VersionedId(a.id(), service.get(a.id()).version())), ApplicationStatus.ANALYZING, null, ACTOR))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void まとめて分析予定にすると受付順に最後尾へ並ぶ() {
        Application first = submit("s1", "@a", "2026-10-01T10:00:00Z");
        Application second = submit("s2", "@b", "2026-10-01T11:00:00Z");
        Application third = submit("s3", "@c", "2026-10-01T12:00:00Z");
        changeStatus(third.id(), ApplicationStatus.SCHEDULED);

        service.bulkChangeStatus(
                List.of(new VersionedId(second.id(), service.get(second.id()).version()),
                        new VersionedId(first.id(), service.get(first.id()).version())),
                ApplicationStatus.SCHEDULED, null, ACTOR);

        assertThat(queue()).containsExactly(third.id(), first.id(), second.id());
    }

    @Test
    void 提督名とXのIDの一部で検索できる() {
        Application alpha = submit("s1", "@Alpha_01", "2026-10-01T10:00:00Z");
        submit("s2", "@bravo", "2026-10-01T11:00:00Z");

        assertThat(service.list(new ApplicationFilter(List.of(), null, null, null, "＠ALPHA"), "queue"))
                .extracting(Application::id).containsExactly(alpha.id());
        assertThat(service.list(new ApplicationFilter(List.of(), null, null, null, "提督s1"), "queue"))
                .extracting(Application::id).containsExactly(alpha.id());
        assertThat(service.list(new ApplicationFilter(List.of(), null, null, null, "zzz"), "queue")).isEmpty();
    }

    // ---- 並べ替え（仕様 5.4） ----

    @Test
    void 次に分析する順は分析予定が先で同じグループ内は受付順() {
        Application early = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application late = submit("s2", "b", "2026-10-01T11:00:00Z");
        Application scheduled = submit("s3", "c", "2026-10-01T12:00:00Z");
        changeStatus(scheduled.id(), ApplicationStatus.SCHEDULED);

        assertThat(queue()).containsExactly(scheduled.id(), early.id(), late.id());
    }

    @Test
    void 手動で並べ替えると動かした応募だけが保存される() {
        Application a = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "b", "2026-10-01T11:00:00Z");
        Application c = submit("s3", "c", "2026-10-01T12:00:00Z");
        long versionOfB = service.get(b.id()).version();

        service.move(c.id(), null); // c を先頭へ
        service.move(a.id(), b.id()); // a を b の後ろへ

        assertThat(queue()).containsExactly(c.id(), b.id(), a.id());
        // 動かしていない b の版は変わらない（b を編集中の人が「先に変更されました」にならない）
        assertThat(service.get(b.id()).version()).isEqualTo(versionOfB);
    }

    @Test
    void 間に入る数がなくなったらグループ全体を振り直す() {
        Application a = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "b", "2026-10-01T10:00:00.001Z"); // a と並び順が1しか違わない
        Application c = submit("s3", "c", "2026-10-01T12:00:00Z");

        service.move(c.id(), a.id()); // a と b の間には数がない

        assertThat(queue()).containsExactly(a.id(), c.id(), b.id());
    }

    // ---- 配信（仕様 7.2） ----

    @Test
    void 配信用の表示にはXのIDと課金額を含めず匿名希望なら名前を伏せる() {
        Application application = service.submit(new IntakeCommand(
                "s1",
                Instant.parse("2026-10-01T10:00:00Z"),
                "2026-10",
                Map.of(
                        "xId", "secret_id",
                        "admiralName", "本名提督",
                        "nameDisplay", "匿名希望",
                        "simulatorUrl", "https://example.com/1",
                        "monthlySpending", "〜3,000円",
                        "goal", "イベント甲完走")));
        changeStatus(application.id(), ApplicationStatus.ANALYZING);

        StreamView view = service.streamView().orElseThrow();
        assertThat(view.displayName()).isEqualTo(ApplicationService.ANONYMOUS_DISPLAY_NAME);
        assertThat(view.answers()).doesNotContainKeys("xId", "monthlySpending", "admiralName");
        assertThat(view.answers()).containsEntry("goal", "イベント甲完走");
        assertThat(view.toString()).doesNotContain("secret_id", "本名提督");
    }

    @Test
    void 比較の相手は前回分析した応募で見送りや落選は相手にしない() {
        Application analyzed = submit("s1", "@a", "2026-09-01T10:00:00Z");
        changeStatus(analyzed.id(), ApplicationStatus.ANALYZING);
        changeStatus(analyzed.id(), ApplicationStatus.DONE);
        Application skipped = submit("s2", "@a", "2026-09-10T10:00:00Z");
        skip(skipped.id(), SkipReason.OTHER);
        Application current = submit("s3", "@a", "2026-10-01T10:00:00Z");

        assertThat(service.previousAnalyzed(service.get(current.id()))).map(Application::id).contains(analyzed.id());
        assertThat(service.previousAnalyzed(service.get(analyzed.id()))).isEmpty();
    }

    @Test
    void 次の人へ進むと分析中の人が分析済みになり次の順番の人が分析中になる() {
        Application current = submit("s1", "@a", "2026-10-01T09:00:00Z");
        Application pending = submit("s2", "@b", "2026-10-01T10:00:00Z");
        Application scheduled = submit("s3", "@c", "2026-10-01T11:00:00Z");
        submit("s4", "@b", "2026-10-01T12:00:00Z"); // 同じ人の2件目。重複の印がつくので飛ばされる
        changeStatus(current.id(), ApplicationStatus.ANALYZING);
        changeStatus(scheduled.id(), ApplicationStatus.SCHEDULED);

        // 分析予定が未着手より先
        Optional<StreamView> view = service.advanceStream(true, ACTOR);
        assertThat(service.get(current.id()).status()).isEqualTo(ApplicationStatus.DONE);
        assertThat(view).map(StreamView::applicationId).contains(scheduled.id());

        // 次は未着手のうち、印のない人
        view = service.advanceStream(true, ACTOR);
        assertThat(view).map(StreamView::applicationId).contains(pending.id());

        // 残りは重複の印つきの2件目だけなので選ばれず、分析済みにするだけで空になる
        view = service.advanceStream(true, ACTOR);
        assertThat(view).isEmpty();
        assertThat(service.get(pending.id()).status()).isEqualTo(ApplicationStatus.DONE);
    }

    @Test
    void 抽選を使う設定なら次の人へは分析予定の人だけから選ぶ() {
        Application current = submit("s1", "@a", "2026-10-01T09:00:00Z");
        submit("s2", "@b", "2026-10-01T10:00:00Z");
        changeStatus(current.id(), ApplicationStatus.ANALYZING);

        // 分析予定の人がいないので、分析済みにするだけ（未着手の人は抽選で選ぶ）
        assertThat(service.advanceStream(false, ACTOR)).isEmpty();
        assertThat(service.get(current.id()).status()).isEqualTo(ApplicationStatus.DONE);
    }

    @Test
    void 分析予定の人は印があっても次の人へで選ばれる() {
        submit("s1", "@a", "2026-10-01T09:00:00Z");
        Application duplicate = submit("s2", "@a", "2026-10-01T10:00:00Z"); // 重複の印つき
        // 配信者さんが印を確かめたうえで分析予定にした
        changeStatus(duplicate.id(), ApplicationStatus.SCHEDULED);

        assertThat(service.advanceStream(true, ACTOR)).map(StreamView::applicationId).contains(duplicate.id());
    }

    // ---- 削除依頼（仕様 8.1） ----

    @Test
    void 削除依頼で同じXのIDの応募と履歴がすべて消える() {
        Application a = submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "a", "2026-10-02T10:00:00Z");
        submit("s3", "b", "2026-10-02T10:00:00Z");
        skip(a.id(), SkipReason.WITHDRAWN);

        service.deleteApplicant(new XId("a"));
        assertThat(repository.findAll()).extracting(app -> app.xId().value()).containsExactly("b");
        assertThat(repository.findHistory(a.id())).isEmpty();
    }
}
