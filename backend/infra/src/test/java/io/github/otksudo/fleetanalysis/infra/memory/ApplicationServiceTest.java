package io.github.otksudo.fleetanalysis.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import io.github.otksudo.fleetanalysis.domain.application.StreamView;
import java.time.Clock;
import java.time.Instant;
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

    @Test
    void 同じ回答が2回届いても1件だけ登録する() {
        Application first = submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        Application second = submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void 未完了の応募がある人の応募には重複の印がつく() {
        submit("s1", "@Alpha", "2026-10-01T10:00:00Z");
        Application second = submit("s2", "alpha", "2026-10-01T11:00:00Z");
        assertThat(second.flags()).extracting(f -> f.type()).containsExactly(FlagType.DUPLICATE);
    }

    @Test
    void 過去に分析済みの人の応募には再応募の印がつく() {
        Application first = submit("s1", "alpha", "2026-10-01T10:00:00Z");
        service.update(first.id(), ApplicationStatus.ANALYZING, null, false, null);
        service.update(first.id(), ApplicationStatus.DONE, null, false, null);

        Application second = submit("s2", "alpha", "2026-10-02T10:00:00Z");
        assertThat(second.flags()).extracting(f -> f.type()).containsExactly(FlagType.REAPPLY);
    }

    @Test
    void 次に分析する順は分析予定が先で同じグループ内は受付順() {
        Application early = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application late = submit("s2", "b", "2026-10-01T11:00:00Z");
        Application scheduled = submit("s3", "c", "2026-10-01T12:00:00Z");
        service.update(scheduled.id(), ApplicationStatus.SCHEDULED, null, false, null);

        List<Application> queue = service.list(List.of(), null, null, "queue");
        assertThat(queue).extracting(Application::id).containsExactly(scheduled.id(), early.id(), late.id());
    }

    @Test
    void 手動で並べ替えられる() {
        Application a = submit("s1", "a", "2026-10-01T10:00:00Z");
        Application b = submit("s2", "b", "2026-10-01T11:00:00Z");
        Application c = submit("s3", "c", "2026-10-01T12:00:00Z");

        service.move(c.id(), null); // c を先頭へ
        service.move(a.id(), b.id()); // a を b の後ろへ

        List<Application> queue = service.list(List.of(), null, null, "queue");
        assertThat(queue).extracting(Application::id).containsExactly(c.id(), b.id(), a.id());
    }

    @Test
    void 分析済みから落選には変えられない() {
        Application application = submit("s1", "a", "2026-10-01T10:00:00Z");
        service.update(application.id(), ApplicationStatus.ANALYZING, null, false, null);
        service.update(application.id(), ApplicationStatus.DONE, null, false, null);

        assertThatThrownBy(() -> service.update(application.id(), ApplicationStatus.LOST, null, false, null))
                .isInstanceOf(ConflictException.class);
    }

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
        service.update(application.id(), ApplicationStatus.ANALYZING, null, false, null);

        StreamView view = service.streamView().orElseThrow();
        assertThat(view.displayName()).isEqualTo(ApplicationService.ANONYMOUS_DISPLAY_NAME);
        assertThat(view.answers()).doesNotContainKeys("xId", "monthlySpending", "admiralName");
        assertThat(view.answers()).containsEntry("goal", "イベント甲完走");
        assertThat(view.toString()).doesNotContain("secret_id", "本名提督");
    }

    @Test
    void 削除依頼で同じXのIDの応募がすべて消える() {
        submit("s1", "a", "2026-10-01T10:00:00Z");
        submit("s2", "a", "2026-10-02T10:00:00Z");
        submit("s3", "b", "2026-10-02T10:00:00Z");

        service.deleteApplicant(new XId("a"));
        assertThat(repository.findAll()).extracting(app -> app.xId().value()).containsExactly("b");
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
    void 次の人へ進むと分析中の人が分析済みになり次の順番の人が分析中になる() {
        Application current = submit("s1", "@a", "2026-10-01T09:00:00Z");
        Application pending = submit("s2", "@b", "2026-10-01T10:00:00Z");
        Application scheduled = submit("s3", "@c", "2026-10-01T11:00:00Z");
        submit("s4", "@b", "2026-10-01T12:00:00Z"); // 同じ人の2件目。重複の印がつくので飛ばされる
        service.update(current.id(), ApplicationStatus.ANALYZING, null, false, null);
        service.update(scheduled.id(), ApplicationStatus.SCHEDULED, null, false, null);

        // 分析予定が未着手より先
        Optional<StreamView> view = service.advanceStream();
        assertThat(service.get(current.id()).status()).isEqualTo(ApplicationStatus.DONE);
        assertThat(view).map(StreamView::applicationId).contains(scheduled.id());

        // 次は未着手のうち、印のない人
        view = service.advanceStream();
        assertThat(view).map(StreamView::applicationId).contains(pending.id());

        // 待っている人がいなければ（残りは重複の印つきだけ）、分析済みにして空になる
        view = service.advanceStream();
        assertThat(view).isEmpty();
        assertThat(service.get(pending.id()).status()).isEqualTo(ApplicationStatus.DONE);
    }
}
