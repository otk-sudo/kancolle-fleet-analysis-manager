package io.github.otksudo.fleetanalysis.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationChanges;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationFilter;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryMode;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import io.github.otksudo.fleetanalysis.domain.lottery.WeightedLottery;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link LotteryService}（抽選の業務ロジック）を、メモリ保存の実装と組み合わせて確かめるテスト。 */
class LotteryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApplicationRepository applications = new InMemoryApplicationRepository();
    private final InMemoryLotteryRepository lotteries = new InMemoryLotteryRepository();
    private final ApplicationService applicationService = new ApplicationService(applications, clock);
    private final LotteryService lotteryService = new LotteryService(applications, lotteries, clock);

    private Application submit(String submissionId, String xId, String receivedAt) {
        return applicationService.submit(new IntakeCommand(
                submissionId,
                Instant.parse(receivedAt),
                "2026-10",
                Map.of("xId", xId, "admiralName", "提督", "simulatorUrl", "https://example.com/")));
    }

    /** 過去の抽選で落選したことにする（「落選」は抽選でだけ付くので、仕組みが行う変更で付ける） */
    private void markLost(Application application) {
        Application loaded = applicationService.get(application.id());
        loaded.changeStatusBySystem(ApplicationStatus.LOST, null, clock.instant(), "tester", "抽選で落選");
        applications.save(loaded);
    }

    @Test
    void 抽選で落選した応募のあとに送られた応募は重複から再応募に付け直される() {
        Application first = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application resent = submit("s2", "a", "2026-10-01T11:00:00Z"); // 重複の印つきなので抽選の対象外
        submit("s3", "b", "2026-10-01T12:00:00Z");

        // 対象は1件目と別の人の2人で、当選は1人。どちらが当たるかは乱数で決まる
        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 1, Set.of(), "tester");
        boolean firstWon = record.entries().stream().anyMatch(e -> e.applicationId().equals(first.id()) && e.won());

        // 1件目が当選（分析予定＝まだ終わっていない）なら「重複」のまま、落選なら「再応募」に変わる
        FlagType expected = firstWon ? FlagType.DUPLICATE : FlagType.REAPPLY;
        assertThat(applicationService.get(resent.id()).flags()).extracting(Flag::type).containsExactly(expected);
    }

    @Test
    void 抽選の途中でほかの人が先に変えた応募も読み直して結果を当てはめ記録は残る() {
        Application a = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "b", "2026-10-01T10:01:00Z");
        // b の保存だけ、1回目は「ほかの人が先に変更した」ことにする保存先
        InMemoryApplicationRepository flaky = new InMemoryApplicationRepository() {
            private boolean failed;

            @Override
            public synchronized void saveAll(List<Application> list) {
                if (!failed && list.stream().anyMatch(app -> app.id().equals(b.id()))) {
                    failed = true;
                    // 実際に誰かがメモを保存して版が進んだ状態を作ってから断る
                    Application other = applications.findById(b.id()).orElseThrow();
                    other.changeMemo("抽選中に保存", clock.instant());
                    applications.save(other);
                    throw new ConflictException("ほかの人が先に変更しました");
                }
                applications.saveAll(list);
            }

            @Override
            public synchronized List<Application> findAll() {
                return applications.findAll();
            }

            @Override
            public synchronized java.util.Optional<Application> findById(String id) {
                return applications.findById(id);
            }

            @Override
            public synchronized List<Application> findByXId(io.github.otksudo.fleetanalysis.domain.XId xId) {
                return applications.findByXId(xId);
            }
        };
        LotteryService service = new LotteryService(flaky, lotteries, clock);

        service.run(LotteryMode.BULK, 1, Set.of(), "tester");

        assertThat(lotteries.findAll()).hasSize(1);
        Application reloadedB = applicationService.get(b.id());
        assertThat(reloadedB.status()).isIn(ApplicationStatus.SCHEDULED, ApplicationStatus.LOST);
        assertThat(reloadedB.memo()).isEqualTo("抽選中に保存"); // ほかの人の変更は消えていない
        assertThat(applicationService.get(a.id()).status()).isIn(ApplicationStatus.SCHEDULED, ApplicationStatus.LOST);
    }

    @Test
    void まとめ抽選では当選者が分析予定に外れた人が落選になる() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "b", "2026-10-01T10:01:00Z");
        submit("s3", "c", "2026-10-01T10:02:00Z");

        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 1, Set.of(), "tester");

        assertThat(record.entries()).hasSize(3);
        assertThat(record.entries()).filteredOn(LotteryRecord.Entry::won).hasSize(1);
        assertThat(applicationService.list(ApplicationFilter.statuses(ApplicationStatus.SCHEDULED), "queue")).hasSize(1);
        assertThat(applicationService.list(ApplicationFilter.statuses(ApplicationStatus.LOST), "queue")).hasSize(2);
    }

    @Test
    void 配信中の抽選では1人が分析中になり外れた人は未着手のまま() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "b", "2026-10-01T10:01:00Z");

        lotteryService.run(LotteryMode.LIVE, 99, Set.of(), "tester");

        assertThat(applicationService.list(ApplicationFilter.statuses(ApplicationStatus.ANALYZING), "queue")).hasSize(1);
        assertThat(applicationService.list(ApplicationFilter.statuses(ApplicationStatus.PENDING), "queue")).hasSize(1);
    }

    @Test
    void 記録した種と対象者から同じ結果を再現できる() {
        for (int i = 0; i < 6; i++) {
            submit("s" + i, "user" + i, "2026-10-01T10:0" + i + ":00Z");
        }
        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 2, Set.of(), "tester");

        List<LotteryEntry> entries = new ArrayList<>();
        for (LotteryRecord.Entry entry : record.entries()) {
            entries.add(new LotteryEntry(entry.applicationId(), entry.weight()));
        }
        List<String> replayed = WeightedLottery.draw(entries, 2, record.seed());
        List<String> recordedWinners = record.entries().stream().filter(LotteryRecord.Entry::won).map(LotteryRecord.Entry::applicationId).toList();
        assertThat(replayed).containsExactlyInAnyOrderElementsOf(recordedWinners);
    }

    @Test
    void 落選回数は最後に当選してから数える() {
        Application lost1 = submit("s1", "a", "2026-09-01T10:00:00Z");
        markLost(lost1);
        Application lost2 = submit("s2", "a", "2026-09-10T10:00:00Z");
        markLost(lost2);
        Application current = submit("s3", "a", "2026-10-01T10:00:00Z");
        assertThat(lotteryService.lossesSinceLastWin(current)).isEqualTo(2);

        // 当選するとリセットされる
        lotteryService.run(LotteryMode.BULK, 1, Set.of(), "tester");
        Application next = submit("s4", "a", "2026-10-02T10:00:00Z");
        assertThat(lotteryService.lossesSinceLastWin(next)).isZero();
    }

    @Test
    void 重複の印がついた応募は初期状態では抽選の対象にならない() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        Application duplicate = submit("s2", "a", "2026-10-01T11:00:00Z");

        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 5, Set.of(), "tester");
        assertThat(record.entries()).extracting(LotteryRecord.Entry::applicationId).doesNotContain(duplicate.id());
    }

    @Test
    void 抽選がオフなら実行できない() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        lotteryService.updateSettings(new LotterySettings(false, true, 1.0), 1);
        assertThatThrownBy(() -> lotteryService.run(LotteryMode.BULK, 1, Set.of(), "tester"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 古い版で設定を保存しようとすると断られる() {
        lotteryService.updateSettings(new LotterySettings(true, false, 1.0), 1);
        assertThatThrownBy(() -> lotteryService.updateSettings(new LotterySettings(true, true, 2.0), 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 印つきを含めても同じ人は1回分しか抽選の対象にならない() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        Application duplicate = submit("s2", "a", "2026-10-01T11:00:00Z");

        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 5, Set.of(duplicate.id()), "tester");
        assertThat(record.entries()).hasSize(1);
    }

    @Test
    void 分析中の人がいるときは配信中の抽選をしない() {
        Application analyzing = submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "b", "2026-10-01T11:00:00Z");
        applicationService.update(analyzing.id(), applicationService.get(analyzing.id()).version(),
                ApplicationChanges.none().withStatus(ApplicationStatus.ANALYZING), "tester");

        assertThatThrownBy(() -> lotteryService.run(LotteryMode.LIVE, 1, Set.of(), "tester"))
                .isInstanceOf(ConflictException.class);
    }
}
