package io.github.otksudo.fleetanalysis.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
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

    @Test
    void まとめ抽選では当選者が分析予定に外れた人が落選になる() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "b", "2026-10-01T10:01:00Z");
        submit("s3", "c", "2026-10-01T10:02:00Z");

        LotteryRecord record = lotteryService.run(LotteryMode.BULK, 1, Set.of(), "tester");

        assertThat(record.entries()).hasSize(3);
        assertThat(record.entries()).filteredOn(LotteryRecord.Entry::won).hasSize(1);
        assertThat(applicationService.list(List.of(ApplicationStatus.SCHEDULED), null, null, "queue")).hasSize(1);
        assertThat(applicationService.list(List.of(ApplicationStatus.LOST), null, null, "queue")).hasSize(2);
    }

    @Test
    void 配信中の抽選では1人が分析中になり外れた人は未着手のまま() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "b", "2026-10-01T10:01:00Z");

        lotteryService.run(LotteryMode.LIVE, 99, Set.of(), "tester");

        assertThat(applicationService.list(List.of(ApplicationStatus.ANALYZING), null, null, "queue")).hasSize(1);
        assertThat(applicationService.list(List.of(ApplicationStatus.PENDING), null, null, "queue")).hasSize(1);
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
        applicationService.update(lost1.id(), ApplicationStatus.LOST, null, false, null);
        Application lost2 = submit("s2", "a", "2026-09-10T10:00:00Z");
        applicationService.update(lost2.id(), ApplicationStatus.LOST, null, false, null);
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
        applicationService.update(analyzing.id(), ApplicationStatus.ANALYZING, null, false, null);

        assertThatThrownBy(() -> lotteryService.run(LotteryMode.LIVE, 1, Set.of(), "tester"))
                .isInstanceOf(ConflictException.class);
    }
}
