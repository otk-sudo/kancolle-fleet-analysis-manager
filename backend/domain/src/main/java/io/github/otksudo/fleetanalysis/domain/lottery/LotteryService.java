package io.github.otksudo.fleetanalysis.domain.lottery;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 抽選の実行と設定（仕様 6章）。 */
public class LotteryService {

    private final ApplicationRepository applications;
    private final LotteryRepository lotteries;
    private final Clock clock;
    /** 乱数の種を作るための乱数。予測されにくい SecureRandom を使う */
    private final SecureRandom seedSource = new SecureRandom();

    public LotteryService(ApplicationRepository applications, LotteryRepository lotteries, Clock clock) {
        this.applications = applications;
        this.lotteries = lotteries;
        this.clock = clock;
    }

    /**
     * 抽選を行い、結果に合わせてステータスを変え、記録を残す。
     *
     * @param mode           まとめ抽選か、配信中の抽選か
     * @param winners        当選人数（配信中の抽選では常に1）
     * @param includeFlagged 印（重複・条件外）がついていても対象に含める応募ID
     * @param executedBy     実行した人
     */
    public LotteryRecord run(LotteryMode mode, int winners, Set<String> includeFlagged, String executedBy) {
        LotterySettings settings = lotteries.loadSettings().settings();
        if (!settings.enabled()) {
            throw new ConflictException("抽選はオフになっています（設定で変更できます）");
        }
        int winnerCount = mode == LotteryMode.LIVE ? 1 : winners;
        if (winnerCount < 1) {
            throw new InvalidValueException("当選人数は1人以上にしてください");
        }

        List<Application> candidates = candidates(includeFlagged);
        if (candidates.isEmpty()) {
            throw new ConflictException("抽選の対象になる「未着手」の応募がありません");
        }

        // 対象者ごとの当たりやすさ
        List<LotteryEntry> entries = new ArrayList<>();
        Map<String, Double> weights = new HashMap<>();
        for (Application candidate : candidates) {
            double weight = settings.weightFor(lossesSinceLastWin(candidate));
            entries.add(new LotteryEntry(candidate.id(), weight));
            weights.put(candidate.id(), weight);
        }

        long seed = seedSource.nextLong();
        Set<String> winnerIds = new HashSet<>(WeightedLottery.draw(entries, winnerCount, seed));

        // 結果に合わせてステータスを変える
        Instant now = clock.instant();
        List<LotteryRecord.Entry> recordEntries = new ArrayList<>();
        for (Application candidate : candidates) {
            boolean won = winnerIds.contains(candidate.id());
            if (won) {
                candidate.markWonLottery();
                candidate.changeStatus(mode == LotteryMode.LIVE ? ApplicationStatus.ANALYZING : ApplicationStatus.SCHEDULED, now);
            } else if (mode == LotteryMode.BULK) {
                candidate.changeStatus(ApplicationStatus.LOST, now);
            }
            applications.save(candidate);
            recordEntries.add(new LotteryRecord.Entry(candidate.id(), weights.get(candidate.id()), won));
        }

        LotteryRecord record = new LotteryRecord(UUID.randomUUID().toString(), mode, now, executedBy, seed, recordEntries);
        lotteries.save(record);
        return record;
    }

    /** 抽選の対象者: 「未着手」で、印（重複・条件外）がないもの。印つきでも includeFlagged に入っていれば対象。 */
    private List<Application> candidates(Set<String> includeFlagged) {
        List<Application> result = new ArrayList<>();
        for (Application application : applications.findAll()) {
            if (application.status() != ApplicationStatus.PENDING) {
                continue;
            }
            if (application.hasBlockingFlag() && !includeFlagged.contains(application.id())) {
                continue;
            }
            result.add(application);
        }
        return result;
    }

    /**
     * 最後に当選してからの落選回数（仕様 6.3）。
     * 同じXのIDの過去の応募を古い順に見ていき、当選があれば0に戻し、落選なら1足す。
     * 抽選画面で「当たりやすさ」の根拠を確かめられるよう、またテストから直接確かめられるよう public にしている。
     */
    public int lossesSinceLastWin(Application candidate) {
        List<Application> past = new ArrayList<>(applications.findByXId(candidate.xId()));
        past.sort(Comparator.comparing(Application::receivedAt));
        int losses = 0;
        for (Application application : past) {
            if (application.id().equals(candidate.id())) {
                continue;
            }
            if (application.wonLottery()) {
                losses = 0;
            } else if (application.status() == ApplicationStatus.LOST) {
                losses++;
            }
        }
        return losses;
    }

    public List<LotteryRecord> history() {
        return lotteries.findAll();
    }

    public LotteryRepository.VersionedSettings settings() {
        return lotteries.loadSettings();
    }

    /** 抽選設定を変える。版が古ければ（誰かが先に変えていれば）{@link ConflictException}。 */
    public LotteryRepository.VersionedSettings updateSettings(LotterySettings settings, int expectedVersion) {
        LotteryRepository.VersionedSettings saved = lotteries.saveSettings(settings, expectedVersion);
        if (saved == null) {
            throw new ConflictException("ほかの人が先に設定を変更しました。画面を読み込み直してください");
        }
        return saved;
    }
}
