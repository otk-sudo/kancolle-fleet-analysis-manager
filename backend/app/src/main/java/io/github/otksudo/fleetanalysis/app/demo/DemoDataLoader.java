package io.github.otksudo.fleetanalysis.app.demo;

import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 画面を試すためのサンプル応募を、起動時に登録する。
 *
 * <p>{@code @Profile("demo")} は「demo プロファイルで起動したときだけ、この部品を使う」という目印。
 * プロファイルは起動時に {@code --spring.profiles.active=demo} などで指定する（scripts/dev.sh で指定済み）。
 * 本番やテストではサンプルが入らないようにするため、プロファイルで分けている。
 *
 * <p>{@code ApplicationRunner} を実装したクラスは、アプリの起動が終わった直後に1回だけ {@link #run} が呼ばれる。
 *
 * <p>XのIDや名前はすべて架空のもの。実在の応募者の情報は使わない（docs/development.md 8章）。
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final String SIMULATOR_URL = "https://noro6.github.io/kc-web/#/";
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final ApplicationService applicationService;
    private final ApplicationRepository repository;
    private final Clock clock;
    private int submissionCount = 0;

    public DemoDataLoader(ApplicationService applicationService, ApplicationRepository repository, Clock clock) {
        this.applicationService = applicationService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        // すでに応募があれば何もしない（DynamoDB Local のように止めてもデータが残る保存先で、
        // 起動するたびにサンプルが増えたり、ステータスの変更が失敗したりしないように）
        if (!repository.findAll().isEmpty()) {
            return;
        }
        // 過去に分析済みの人が、もう一度応募している（「再応募」の印がつき、配信用画面で前回と比べられる）
        Application past = submit(60, "demo_teitoku01", "朝霧", false, "1〜3年", "〜3,000円", "継続3群", "イベント",
                "E-3甲を突破したい", null);
        changeStatus(past, ApplicationStatus.ANALYZING, ApplicationStatus.DONE);
        Application current = submit(3, "demo_teitoku01", "朝霧", false, "3〜5年", "〜5,000円", "継続2群", "イベント",
                "次のイベントで全海域甲を目指したい", "前回のアドバイスで基地航空隊を見直しました");
        changeStatus(current, ApplicationStatus.ANALYZING);

        // 分析予定（配信日が決まっている人と、まだの人）
        Application scheduled1 = submit(10, "demo_teitoku02", "夕凪", true, "半年〜1年", "0円", "戦果やらない", "全体的な育成方針",
                "まずは改二を増やしたい", null);
        changeStatus(scheduled1, ApplicationStatus.SCHEDULED);
        applicationService.update(scheduled1.id(), null, LocalDate.now(clock.withZone(JAPAN)).plusDays(3), false, null);
        Application scheduled2 = submit(9, "demo_teitoku03", "白露", false, "5〜10年", "〜10,000円", "継続1群", "演習",
                "演習で勝率を上げたい", null);
        changeStatus(scheduled2, ApplicationStatus.SCHEDULED);

        // 未着手（目的や回答をばらけさせる）
        submit(8, "demo_teitoku04", "東雲", false, "10年以上", "10,000円超", "継続聯合", "イベント", "甲勲章を全部集めたい", null);
        submit(7, "demo_teitoku05", "霧島", true, "1〜3年", "回答しない", "クォータリー3群", "通常海域", "5-5を安定して周回したい",
                "縛りなしで遊んでいます");
        submit(6, "demo_teitoku06", "秋月", false, "3〜5年", "〜3,000円", "継続3群", "通常海域", "7-4の攻略", null);
        submit(5, "demo_teitoku07", "浜風", false, "半年未満", "0円", "戦果やらない", "全体的な育成方針", "何から育てればいいか知りたい", null);
        submit(4, "demo_teitoku08", "摩耶", false, "5〜10年", "〜5,000円", "継続2群", "演習", "対潜の編成を見直したい", null);
        submit(2, "demo_teitoku09", "長月", true, "1〜3年", "〜3,000円", "その他", "その他", "装備の改修の優先度を知りたい", null);

        // 同じ人が2回送ってきた（「重複」の印がつく）
        submit(4, "demo_teitoku04", "東雲", false, "10年以上", "10,000円超", "継続聯合", "イベント", "甲勲章を全部集めたい（再送）", null);

        // 前回は抽選で落選した人が、もう一度応募している（落選補正で当たりやすくなる）
        Application lost = submit(40, "demo_teitoku10", "雪風", false, "3〜5年", "〜5,000円", "継続3群", "イベント", "初めての甲作戦", null);
        changeStatus(lost, ApplicationStatus.LOST);
        submit(1, "demo_teitoku10", "雪風", false, "3〜5年", "〜5,000円", "継続3群", "イベント", "今度こそ甲作戦", null);

        // 見送り
        Application skipped = submit(20, "demo_teitoku11", "天霧", false, "1〜3年", "0円", "戦果やらない", "通常海域", "1-5のクリア", null);
        changeStatus(skipped, ApplicationStatus.SKIPPED);
    }

    private Application submit(
            int daysAgo,
            String xId,
            String admiralName,
            boolean anonymous,
            String activePeriod,
            String monthlySpending,
            String rankingEffort,
            String purpose,
            String goal,
            String comment) {
        submissionCount++;
        // gas/Code.gs の ITEM_CODES と同じ項目コードで回答を作る
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("xId", "@" + xId);
        answers.put("admiralName", admiralName);
        answers.put("nameDisplay", anonymous ? "匿名希望" : "提督名でOK");
        answers.put("simulatorUrl", SIMULATOR_URL);
        answers.put("startedAt", "2020-04");
        answers.put("activePeriod", activePeriod);
        answers.put("monthlySpending", monthlySpending);
        answers.put("dailyPlayTime", "〜1時間");
        answers.put("rankingEffort", rankingEffort);
        answers.put("hasRestrictions", "なし");
        answers.put("goal", goal);
        answers.put("purpose", purpose);
        if (comment != null) {
            answers.put("comment", comment);
        }
        // 同じ日の応募でも受付順がばらけるよう、件数ぶん分をずらす
        Instant submittedAt = clock.instant().minus(Duration.ofDays(daysAgo)).plus(Duration.ofMinutes(submissionCount));
        return applicationService.submit(new IntakeCommand("demo-" + submissionCount, submittedAt, "v1", answers));
    }

    /** 許された順番でステータスを変えていく（例: 未着手 → 分析中 → 分析済み） */
    private void changeStatus(Application application, ApplicationStatus... steps) {
        for (ApplicationStatus step : steps) {
            applicationService.update(application.id(), step, null, false, null);
        }
    }
}
