package io.github.otksudo.fleetanalysis.domain.lottery;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
     */
    public LotteryRecord run(LotteryMode mode, int winners, Set<String> includeFlagged) {
        // 応募の変更（ApplicationService）と同じ鍵を使い、2つの抽選や受付・変更が同時に混ざらないようにする。
        // 途中まで変更して記録が残らない、という事態を防ぐため
        synchronized (applications) {
            return runLocked(mode, winners, includeFlagged);
        }
    }

    private LotteryRecord runLocked(LotteryMode mode, int winners, Set<String> includeFlagged) {
        LotterySettings settings = lotteries.loadSettings().settings();
        if (!settings.enabled()) {
            throw new ConflictException("抽選はオフになっています（設定で変更できます）");
        }
        int winnerCount = mode == LotteryMode.LIVE ? 1 : winners;
        if (winnerCount < 1) {
            throw new InvalidValueException("当選人数は1人以上にしてください");
        }

        if (mode == LotteryMode.LIVE && someoneAnalyzing()) {
            // 分析中の人がいるまま抽選すると、配信用画面にどちらを出すか紛らわしくなるため断る
            throw new ConflictException("「分析中」の応募があります。先に「分析済み」などに変えてから抽選してください");
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

        // 結果に合わせてステータスを変える（抽選は仕組みが行う変更なので、手で変えるときの表は使わない。仕様 5.1）
        Instant now = clock.instant();
        Map<String, Application> changed = new LinkedHashMap<>();
        Set<XId> affected = new HashSet<>();
        List<LotteryRecord.Entry> recordEntries = new ArrayList<>();
        long lastScheduled = lastScheduledPosition();
        for (Application candidate : candidates) {
            boolean won = winnerIds.contains(candidate.id());
            if (won) {
                candidate.markWonLottery();
                candidate.changeStatusBySystem(
                        mode == LotteryMode.LIVE ? ApplicationStatus.ANALYZING : ApplicationStatus.SCHEDULED,
                        null, now, "抽選で当選");
                // 「分析予定」の最後尾に並べる（candidates は受付順なので、当選者どうしは受付順になる）
                lastScheduled += 1000;
                candidate.changePosition(lastScheduled);
                changed.put(candidate.id(), candidate);
                affected.add(candidate.xId());
            } else if (mode == LotteryMode.BULK) {
                candidate.changeStatusBySystem(ApplicationStatus.LOST, null, now, "抽選で落選");
                changed.put(candidate.id(), candidate);
                affected.add(candidate.xId());
            }
            recordEntries.add(new LotteryRecord.Entry(candidate.id(), weights.get(candidate.id()), won));
        }
        // 抽選でステータスが変わった人の、ほかの応募の印を付け直す（例: 落選した応募の後に送られた応募は「重複」から「再応募」へ）
        ApplicationService.refreshFlags(applications, affected, changed);

        // 抽選記録を先に保存する。応募の保存が途中で失敗しても、記録があれば抽選の取り消し（段階8）で元に戻せるため。
        // 逆の順番だと、「落選」になったのに記録がない（取り消せない）応募が残ってしまう
        LotteryRecord record = new LotteryRecord(UUID.randomUUID().toString(), mode, now, seed, recordEntries);
        lotteries.save(record);

        // 抽選の対象者は数百人になることがあり、1回のトランザクション（最大100件）に収まらないため、1件ずつ保存する。
        // TODO(段階8): 抽選の取り消しを作るときに、全部を確実にそろえる方法（再実行など）を見直す
        Set<String> candidateIds = candidateIds(candidates);
        for (Application application : changed.values()) {
            saveRetryingOnConflict(application, candidateIds.contains(application.id()) ? winnerIds : null,
                    mode, now);
        }
        return record;
    }

    private static Set<String> candidateIds(List<Application> candidates) {
        Set<String> ids = new HashSet<>();
        for (Application candidate : candidates) {
            ids.add(candidate.id());
        }
        return ids;
    }

    /**
     * 応募を1件保存する。抽選の途中で、ほかの人がその応募を先に変更していた（版が違う）ときは、
     * 最新の内容を読み直して、同じ結果をもう一度当てはめてから保存し直す。
     * 読み直したらもう「未着手」でなかった（抽選の間に手で変えられた）応募には、抽選の結果を当てはめない。
     *
     * @param winnerIds 抽選の対象者なら当選者のID。印の付け直しだけの応募なら null
     */
    private void saveRetryingOnConflict(
            Application application, Set<String> winnerIds, LotteryMode mode, Instant now) {
        try {
            applications.save(application);
            return;
        } catch (ConflictException e) {
            // 下で読み直してやり直す
        }
        Application fresh = applications.findById(application.id()).orElse(null);
        if (fresh == null) {
            return; // 抽選の間に消された（削除依頼）
        }
        if (winnerIds == null) {
            // 印の付け直しだけ。最新の内容で印を決め直す
            Map<String, Application> changed = new LinkedHashMap<>();
            changed.put(fresh.id(), fresh);
            ApplicationService.refreshFlags(applications, Set.of(fresh.xId()), changed);
            applications.save(fresh);
            return;
        }
        if (fresh.status() != ApplicationStatus.PENDING) {
            return;
        }
        if (winnerIds.contains(fresh.id())) {
            fresh.markWonLottery();
            fresh.changeStatusBySystem(
                    mode == LotteryMode.LIVE ? ApplicationStatus.ANALYZING : ApplicationStatus.SCHEDULED,
                    null, now, "抽選で当選");
            fresh.changePosition(application.position());
        } else if (mode == LotteryMode.BULK) {
            fresh.changeStatusBySystem(ApplicationStatus.LOST, null, now, "抽選で落選");
        }
        fresh.replaceFlags(application.flags());
        applications.save(fresh);
    }

    /**
     * 抽選の対象者: 「未着手」で、印（重複・条件外）がないもの。印つきでも includeFlagged に入っていれば対象。
     * 同じ人（同じXのID）の応募が複数あっても、1人1回分だけ対象にする（受付が早いほうを使う）。
     */
    private List<Application> candidates(Set<String> includeFlagged) {
        List<Application> pending = new ArrayList<>();
        for (Application application : applications.findAll()) {
            if (application.status() != ApplicationStatus.PENDING) {
                continue;
            }
            if (application.hasBlockingFlag() && !includeFlagged.contains(application.id())) {
                continue;
            }
            pending.add(application);
        }
        pending.sort(Comparator.comparing(Application::receivedAt));

        List<Application> result = new ArrayList<>();
        Set<XId> seen = new HashSet<>();
        for (Application application : pending) {
            if (seen.add(application.xId())) {
                result.add(application);
            }
        }
        return result;
    }

    /** 「分析予定」のグループの最後尾の並び順。誰もいなければ 0 */
    private long lastScheduledPosition() {
        long last = 0;
        for (Application application : applications.findAll()) {
            if (application.status() == ApplicationStatus.SCHEDULED && application.position() > last) {
                last = application.position();
            }
        }
        return last;
    }

    private boolean someoneAnalyzing() {
        for (Application application : applications.findAll()) {
            if (application.status() == ApplicationStatus.ANALYZING) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最後に当選してからの落選回数（仕様 6.3）。
     * 同じXのIDの過去の応募を古い順に見ていき、当選があれば0に戻し、落選なら1足す。
     * 「落選」は抽選でだけ付く（手では付けられない。仕様 5.1）ので、実際の抽選の回数と一致する。
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
