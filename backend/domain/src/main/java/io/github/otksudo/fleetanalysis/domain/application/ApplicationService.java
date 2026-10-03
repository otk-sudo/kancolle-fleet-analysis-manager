package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import io.github.otksudo.fleetanalysis.domain.XId;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 応募の受付・一覧・ステータス変更などの業務処理（仕様 5章）。
 *
 * <p>「サービス」は、複数のデータや業務ルールを組み合わせて1つの操作（ユースケース）を行うクラス。
 * 保存先（{@link ApplicationRepository}）と時計（{@link Clock}）は外から渡してもらう（依存性の注入）。
 * テストでは時計を固定したものに差し替えられるので、日時に左右されないテストが書ける。
 */
public class ApplicationService {

    /** 配信用画面で匿名希望の人の代わりに出す名前 */
    public static final String ANONYMOUS_DISPLAY_NAME = "匿名提督";

    private final ApplicationRepository repository;
    private final Clock clock;

    public ApplicationService(ApplicationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * フォームからの応募を受け付ける（仕様 3章、5.2）。
     *
     * <p>同じ回答が2回送られてきた場合（通信のやり直しなど）は、1件目をそのまま返して二重登録しない。
     */
    public Application submit(IntakeCommand command) {
        // 「同じ回答IDがすでにあるか確かめる」と「保存する」の間に別のリクエストが割り込むと、二重に登録されてしまう。
        // 保存先を鍵（ロック）にして、同時に1つずつしか動かないようにする（抽選などほかの変更も同じ鍵を使う）。
        // ただしこの鍵は1つのサーバーの中でしか効かない。本番（AWS Lambda）では複数のサーバーが同時に動くので、
        // 保存先も「同じ回答IDがすでに別の応募として保存されていたら断る」ようにしてある（ConflictException）。
        // 断られたときは、先に保存された応募を返す（同じ回答の再送なので、受付済みとして扱ってよい）。
        synchronized (repository) {
            try {
                return submitLocked(command);
            } catch (ConflictException e) {
                return repository.findBySubmissionId(command.submissionId()).orElseThrow(() -> e);
            }
        }
    }

    private Application submitLocked(IntakeCommand command) {
        Optional<Application> existing = repository.findBySubmissionId(command.submissionId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<String, Object> answers = command.answers();
        // 項目コードが空の回答は保存できない（DynamoDB は空の名前を受け付けない）ので、入力の誤りとして断る
        for (String key : answers.keySet()) {
            if (key == null || key.isBlank()) {
                throw new InvalidValueException("回答の項目コードが空です");
            }
        }
        XId xId = XId.parse(requiredText(answers, AnswerKeys.X_ID));
        String admiralName = requiredText(answers, AnswerKeys.ADMIRAL_NAME);
        String simulatorUrl = requiredUrl(answers, AnswerKeys.SIMULATOR_URL);
        boolean anonymous = AnswerKeys.ANONYMOUS_ANSWER.equals(answers.get(AnswerKeys.NAME_DISPLAY));

        // 保存する回答では、XのIDを正規化した値に置き換えておく
        Map<String, Object> normalizedAnswers = new LinkedHashMap<>(answers);
        normalizedAnswers.put(AnswerKeys.X_ID, xId.value());

        Application application = new Application(
                UUID.randomUUID().toString(),
                command.submissionId(),
                xId,
                admiralName,
                anonymous,
                simulatorUrl,
                command.formVersion(),
                normalizedAnswers,
                command.submittedAt(),
                List.of());
        // 新しい応募の印を決め、同じ人のほかの応募の印も付け直す。
        // Apps Script の再送では、送れなかった古い回答があとから届く（受付日時は回答した日時のまま）ので、
        // 新しい応募のほうが古いこともある。そのため、同じ人の応募すべてについて決め直す
        Map<String, Application> changed = new LinkedHashMap<>();
        changed.put(application.id(), application);
        refreshFlags(repository, Set.of(xId), changed);
        repository.saveAll(List.copyOf(changed.values()));
        return application;
    }

    /**
     * 応募の一覧（仕様 5.4、7.1）。
     *
     * @param filter 絞り込み・検索の条件
     * @param order  "queue"（次に分析する順）または "received"（受付順）
     */
    public List<Application> list(ApplicationFilter filter, String order) {
        String keyword = normalizeKeyword(filter.keyword());
        List<Application> result = new ArrayList<>();
        for (Application application : repository.findAll()) {
            if (!filter.statuses().isEmpty() && !filter.statuses().contains(application.status())) {
                continue;
            }
            if (filter.purpose() != null && !answerMatches(application, AnswerKeys.PURPOSE, filter.purpose())) {
                continue;
            }
            if (filter.rankingEffort() != null
                    && !answerMatches(application, AnswerKeys.RANKING_EFFORT, filter.rankingEffort())) {
                continue;
            }
            if (filter.flag() != null && !hasFlag(application, filter.flag())) {
                continue;
            }
            if (keyword != null && !matchesKeyword(application, keyword)) {
                continue;
            }
            result.add(application);
        }

        if ("received".equals(order)) {
            result.sort(ApplicantFlags.RECEIVED_ORDER);
        } else {
            result.sort(QUEUE_ORDER);
        }
        return result;
    }

    /**
     * 回答が指定の値と同じか。チェックボックスの質問は回答がリストになるので、そのときは「含むか」で判断する。
     */
    private static boolean answerMatches(Application application, String key, String expected) {
        Object value = application.answers().get(key);
        if (value instanceof List<?> list) {
            return list.contains(expected);
        }
        return expected.equals(value);
    }

    /**
     * 検索の言葉をそろえる: 全角を半角に（NFKC）、前後の空白と先頭の@を除き、小文字にする。
     * XのIDは保存時に同じやり方でそろえているので、「@Teitoku」でも「ｔｅｉｔｏｋｕ」でも見つかる。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = Normalizer.normalize(keyword, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean matchesKeyword(Application application, String keyword) {
        String name = Normalizer.normalize(application.admiralName(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return name.contains(keyword) || application.xId().value().contains(keyword);
    }

    /**
     * 「次に分析する順」の並べ方（仕様 5.4）。
     * 分析中 → 分析予定 → 未着手 → それ以外 の順に並べ、同じグループの中は並び順（position）の小さい順。
     */
    private static final Comparator<Application> QUEUE_ORDER =
            Comparator.comparingInt((Application a) -> queueGroup(a.status()))
                    .thenComparingLong(Application::position)
                    .thenComparing(ApplicantFlags.RECEIVED_ORDER);

    private static int queueGroup(ApplicationStatus status) {
        return switch (status) {
            case ANALYZING -> 0;
            case SCHEDULED -> 1;
            case PENDING -> 2;
            default -> 3;
        };
    }

    private static boolean hasFlag(Application application, FlagType type) {
        for (Flag flag : application.flags()) {
            if (flag.type() == type) {
                return true;
            }
        }
        return false;
    }

    public Application get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("応募が見つかりません: " + id));
    }

    /** 応募の変更履歴（古い順）。仕様 5.1、5.6 */
    public List<HistoryEntry> history(String id) {
        get(id); // 応募がなければ 404 にするため
        return repository.findHistory(id);
    }

    /**
     * 画面で読み込んだときの版と、今の版が同じか確かめる（仕様 5.1 の同時変更の検知）。
     * 違えば、ほかの人が先に変更している。そのまま保存すると相手の変更を消してしまうので断る。
     */
    private static void checkVersion(Application application, long expectedVersion) {
        if (application.version() != expectedVersion) {
            throw new ConflictException(
                    "ほかの人が先にこの応募を変更しました。画面を読み込み直してから、もう一度変更してください");
        }
    }

    /**
     * 応募詳細での変更（ステータス、見送りの理由、配信日、メモ、分析メモ、アーカイブURL、XのID）。
     * {@code changes} の null の項目は変えない。
     *
     * @param expectedVersion 画面で読み込んだときの版
     */
    public Application update(String id, long expectedVersion, ApplicationChanges changes) {
        synchronized (repository) {
            Application application = get(id);
            checkVersion(application, expectedVersion);
            Instant now = clock.instant();
            Set<XId> affected = new HashSet<>();
            affected.add(application.xId());

            if (changes.xId() != null) {
                XId next = XId.parse(changes.xId());
                application.changeXId(next, now);
                affected.add(next);
            }
            if (changes.status() != null) {
                ApplicationStatus before = application.status();
                application.changeStatus(changes.status(), changes.skipReason(), now);
                if (before != application.status()) {
                    placeAtEndOfGroup(repository, application);
                }
            } else if (changes.skipReason() != null) {
                application.changeSkipReason(changes.skipReason(), now);
            }
            if (changes.clearStreamDate()) {
                application.changeStreamDate(null, now);
            } else if (changes.streamDate() != null) {
                application.changeStreamDate(changes.streamDate(), now);
            }
            if (changes.memo() != null) {
                application.changeMemo(changes.memo(), now);
            }
            if (changes.analysisMemo() != null) {
                application.changeAnalysisMemo(changes.analysisMemo(), now);
            }
            if (changes.archiveUrl() != null) {
                String url = changes.archiveUrl().strip();
                application.changeArchiveUrl(url.isEmpty() ? null : checkUrl(url, "配信アーカイブのURL"), now);
            }

            Map<String, Application> changed = new LinkedHashMap<>();
            changed.put(application.id(), application);
            refreshFlags(repository, affected, changed);
            repository.saveAll(List.copyOf(changed.values()));
            return application;
        }
    }

    /**
     * 1回のまとめてのステータス変更で変えられる最大の件数（DynamoDB のトランザクションの最大100件に収まるように）。
     * 1件につき応募と履歴の2件を書き、さらに印が変わった同じ人の応募も一緒に書くので、余裕をもって25件にしている。
     * それでも超えたときは、保存先が「件数を減らしてやり直して」と断る（何も保存しない）。
     */
    public static final int BULK_LIMIT = 25;

    /** まとめて変えられるステータス */
    public static final Set<ApplicationStatus> BULK_STATUSES =
            Set.of(ApplicationStatus.PENDING, ApplicationStatus.SCHEDULED, ApplicationStatus.SKIPPED);

    /**
     * 複数の応募のステータスをまとめて変える（仕様 5.6）。例: 条件外の応募をまとめて見送りにする。
     *
     * <p>全部変えられるときだけ変える。1件でも変えられない（表で許されない、ほかの人が先に変えた）なら、
     * どれも変えずに {@link ConflictException}。半分だけ変わると、どこまで変わったか確かめる手間が増えるため。
     *
     * @param targets    変える応募のIDと、画面で読み込んだときの版
     * @param status     新しいステータス
     * @param skipReason 見送りの理由（見送りにするときは必須）
     * @return 変更後の応募（targets と同じ順）
     */
    public List<Application> bulkChangeStatus(
            List<VersionedId> targets, ApplicationStatus status, SkipReason skipReason) {
        if (targets.isEmpty()) {
            throw new InvalidValueException("変える応募を選んでください");
        }
        if (targets.size() > BULK_LIMIT) {
            throw new InvalidValueException("まとめて変えられるのは" + BULK_LIMIT + "件までです");
        }
        if (!BULK_STATUSES.contains(status)) {
            // 「分析中」を何人も同時に作ると配信用画面に誰を出すか紛らわしく、「分析済み」は1人ずつ確かめて付けるため
            throw new InvalidValueException("まとめて変えられるのは「未着手」「分析予定」「見送り」だけです");
        }
        if (status == ApplicationStatus.SKIPPED && skipReason == null) {
            throw new InvalidValueException("見送りにするときは理由を選んでください");
        }
        if (status != ApplicationStatus.SKIPPED && skipReason != null) {
            throw new InvalidValueException("見送りの理由は、見送りにするときだけ選べます");
        }
        synchronized (repository) {
            Instant now = clock.instant();
            List<Application> targetsLoaded = new ArrayList<>();
            List<Application> moved = new ArrayList<>(); // ステータスが変わった応募（並び順を決め直す）
            List<String> problems = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (VersionedId target : targets) {
                if (!seen.add(target.id())) {
                    throw new InvalidValueException("同じ応募が2回選ばれています: " + target.id());
                }
                Application application = get(target.id());
                if (application.version() != target.version()) {
                    problems.add(application.admiralName() + "（ほかの人が先に変更しました）");
                    continue;
                }
                ApplicationStatus before = application.status();
                try {
                    application.changeStatus(status, skipReason, now);
                } catch (ConflictException e) {
                    problems.add(application.admiralName() + "（" + e.getMessage() + "）");
                    continue;
                }
                targetsLoaded.add(application);
                if (before != application.status()) {
                    moved.add(application);
                }
            }
            if (!problems.isEmpty()) {
                throw new ConflictException("次の応募は変更できないため、どれも変更しませんでした: " + String.join("、", problems));
            }

            placeAllAtEndOfGroup(moved);
            Map<String, Application> changed = new LinkedHashMap<>();
            Set<XId> affected = new HashSet<>();
            for (Application application : targetsLoaded) {
                changed.put(application.id(), application);
                affected.add(application.xId());
            }
            refreshFlags(repository, affected, changed);
            repository.saveAll(List.copyOf(changed.values()));
            return targetsLoaded;
        }
    }

    /** まとめてのステータス変更で使う「応募IDと版」の組。 */
    public record VersionedId(String id, long version) {
    }

    /**
     * まとめて「未着手」「分析予定」に移った応募を、受付順にそのグループの最後尾へ並べる。
     * 1件ずつ {@link #placeAtEndOfGroup} を使うと、まだ保存していない仲間を数えずに同じ位置へ入れてしまうため。
     */
    private void placeAllAtEndOfGroup(List<Application> moved) {
        List<Application> sorted = new ArrayList<>(moved);
        sorted.sort(ApplicantFlags.RECEIVED_ORDER);
        Set<String> movedIds = new HashSet<>();
        for (Application application : moved) {
            movedIds.add(application.id());
        }
        List<Application> others = repository.findAll();
        for (ApplicationStatus group : List.of(ApplicationStatus.PENDING, ApplicationStatus.SCHEDULED)) {
            long last = 0;
            for (Application other : others) {
                if (other.status() == group && !movedIds.contains(other.id()) && other.position() > last) {
                    last = other.position();
                }
            }
            for (Application application : sorted) {
                if (application.status() == group) {
                    last += 1000;
                    application.changePosition(last);
                }
            }
        }
    }

    /**
     * 重複の解消: この応募を残し、同じ人のほかの「未着手」「分析予定」の応募を「見送り（重複（再送のため））」にする（仕様 5.2）。
     * 応募者が内容を直して送り直したときに使う。
     *
     * <p>見送りにする中に「分析予定」（抽選の当選を含む）があれば、残す応募がそのステータス・並び順・当選の扱いを引き継ぐ。
     * 送り直しで当選が無駄にならないように。
     * 同じ人の応募に「分析中」があるときは使えない（先に分析を終えるか中断する）。
     *
     * @param expectedVersion 残す応募の、画面で読み込んだときの版
     * @return 残した応募（引き継いだあと）
     */
    public Application keep(String id, long expectedVersion) {
        synchronized (repository) {
            Application target = get(id);
            checkVersion(target, expectedVersion);
            if (!target.status().isQueued()) {
                throw new ConflictException("残せるのは「未着手」か「分析予定」の応募だけです");
            }

            List<Application> others = new ArrayList<>();
            for (Application other : repository.findByXId(target.xId())) {
                if (other.id().equals(target.id())) {
                    continue;
                }
                if (other.status() == ApplicationStatus.ANALYZING) {
                    throw new ConflictException("同じ人の応募に「分析中」があります。先に分析を終えるか中断してください");
                }
                if (other.status().isQueued()) {
                    others.add(other);
                }
            }
            if (others.isEmpty()) {
                throw new ConflictException("見送りにする同じ人の「未着手」「分析予定」の応募がありません");
            }

            Instant now = clock.instant();
            // 見送りにする中の「分析予定」を引き継ぐ。並び順は、その中で一番前のもの（残す応募が分析予定なら、それも比べる）
            Long inheritedPosition = target.status() == ApplicationStatus.SCHEDULED ? target.position() : null;
            boolean inheritedWin = false;
            for (Application other : others) {
                if (other.status() == ApplicationStatus.SCHEDULED) {
                    inheritedPosition = inheritedPosition == null ? other.position() : Math.min(inheritedPosition, other.position());
                    inheritedWin |= other.wonLottery();
                }
            }
            if (inheritedPosition != null) {
                target.changeStatusBySystem(ApplicationStatus.SCHEDULED, null, now, "重複の解消（分析予定を引き継ぎ）");
                target.changePosition(inheritedPosition);
                if (inheritedWin) {
                    target.markWonLottery();
                }
            }
            for (Application other : others) {
                other.changeStatusBySystem(ApplicationStatus.SKIPPED, SkipReason.RESUBMITTED, now, "重複の解消");
            }

            Map<String, Application> changed = new LinkedHashMap<>();
            changed.put(target.id(), target);
            for (Application other : others) {
                changed.put(other.id(), other);
            }
            refreshFlags(repository, Set.of(target.xId()), changed);
            repository.saveAll(List.copyOf(changed.values()));
            return target;
        }
    }

    /**
     * 「次に分析する順」を手で並べ替える（仕様 5.4）。
     *
     * <p>同じグループ（分析予定どうし、未着手どうし）の中で、{@code afterId} の直後に移動する。
     * {@code afterId} が null ならグループの先頭へ。
     *
     * <p>並び順（position）は、前後の応募の値の「間の値」にする。こうすると動かした1件だけを保存すればよく、
     * 動かしていない応募の版は変わらない（ほかの人がその応募を編集中でも、並べ替えのせいで「先に変更されました」にならない）。
     * ただし動かした応募は保存するので版が1つ進む（その応募の詳細を開いている人は、保存すると読み込み直しを求められる）。
     * 間に入る数がもうないときだけ、グループ全体を 1000, 2000, 3000... と振り直す（このときは振り直した応募の版が進む）。
     */
    public void move(String id, String afterId) {
        synchronized (repository) {
            moveLocked(id, afterId);
        }
    }

    private void moveLocked(String id, String afterId) {
        if (id.equals(afterId)) {
            throw new InvalidValueException("自分自身の後ろには移動できません");
        }
        Application target = get(id);
        ApplicationStatus group = target.status();
        if (!group.isQueued()) {
            throw new InvalidValueException("並べ替えできるのは「未着手」と「分析予定」の応募だけです");
        }

        List<Application> sameGroup = list(ApplicationFilter.statuses(group), "queue");
        sameGroup.removeIf(a -> a.id().equals(id));

        int insertAt = 0;
        if (afterId != null) {
            insertAt = -1;
            for (int i = 0; i < sameGroup.size(); i++) {
                if (sameGroup.get(i).id().equals(afterId)) {
                    insertAt = i + 1;
                    break;
                }
            }
            if (insertAt < 0) {
                throw new InvalidValueException("移動先の応募が同じステータスの中にありません: " + afterId);
            }
        }

        Long before = insertAt > 0 ? sameGroup.get(insertAt - 1).position() : null;
        Long after = insertAt < sameGroup.size() ? sameGroup.get(insertAt).position() : null;
        if (before == null && after == null) {
            return; // グループに自分しかいない
        }
        if (before == null) {
            target.changePosition(after - 1000);
        } else if (after == null) {
            target.changePosition(before + 1000);
        } else if (after - before >= 2) {
            target.changePosition(before + (after - before) / 2);
        } else {
            // 間に入る数がないので、グループ全体を振り直す（変わったものだけ保存する）
            sameGroup.add(insertAt, target);
            for (int i = 0; i < sameGroup.size(); i++) {
                Application application = sameGroup.get(i);
                long position = (i + 1) * 1000L;
                if (application.position() != position) {
                    application.changePosition(position);
                    repository.save(application);
                }
            }
            return;
        }
        repository.save(target);
    }

    /** 同じ応募者の応募（新しい順）。仕様 5.5 */
    public List<Application> sameApplicant(XId xId) {
        List<Application> result = new ArrayList<>(repository.findByXId(xId));
        result.sort(ApplicantFlags.RECEIVED_ORDER.reversed());
        return result;
    }

    /**
     * 比較の相手になる「前回分析した応募」（仕様 5.5）。
     * 同じXのIDの「分析済み」の応募のうち、この応募より前に受け付けた中で一番新しいもの。
     * 見送り・落選の応募は分析していないので、比較の相手にしない。
     */
    public Optional<Application> previousAnalyzed(Application application) {
        Application result = null;
        for (Application other : repository.findByXId(application.xId())) {
            if (other.status() != ApplicationStatus.DONE || other.id().equals(application.id())
                    || ApplicantFlags.RECEIVED_ORDER.compare(other, application) >= 0) {
                continue;
            }
            if (result == null || ApplicantFlags.RECEIVED_ORDER.compare(other, result) > 0) {
                result = other;
            }
        }
        return Optional.ofNullable(result);
    }

    /** 削除依頼への対応（仕様 8.1）。 */
    public void deleteApplicant(XId xId) {
        // TODO(段階6): 抽選記録に残る応募IDを「削除済み」に置き換える（仕様 8.1）
        synchronized (repository) {
            repository.deleteByXId(xId);
        }
    }

    /**
     * ステータスが「未着手」「分析予定」に変わった応募を、そのグループの最後尾に置く（仕様 5.4）。
     *
     * <p>並び順（position）はグループの中だけで意味を持つ。手で並べ替えたグループは並び順の値が変わっているので、
     * 別のグループから移ってきた応募の並び順をそのままにすると、先頭など思わぬ場所に入ってしまう。
     * 抽選で当選して「分析予定」になったときにも使うため、static にして LotteryService からも呼べるようにしている。
     */
    public static void placeAtEndOfGroup(ApplicationRepository repository, Application application) {
        ApplicationStatus group = application.status();
        if (!group.isQueued()) {
            return;
        }
        long max = 0;
        for (Application other : repository.findAll()) {
            if (other.status() == group && !other.id().equals(application.id()) && other.position() > max) {
                max = other.position();
            }
        }
        application.changePosition(max + 1000);
    }

    /**
     * 同じ人（{@code xIds}）の応募の印を付け直す（仕様 5.2。決め方は {@link ApplicantFlags}）。
     *
     * <p>{@code changed} には、この操作で変えた（まだ保存していない）応募を入れて渡す。
     * 保存先から読み込んだ古い内容ではなく、こちらを使って印を決める。
     * 印が変わった応募は {@code changed} に足すので、呼び出した側がまとめて保存する。
     * 抽選（LotteryService）からも使うため static にしている。
     *
     * <p>限界: 同じ人の応募は索引（GSI3）から探すので、ほぼ同時（1秒未満）に別のサーバーで保存された応募は見えないことがある。
     * たとえば同じ人の2件の応募がほぼ同時に届くと、どちらにも「重複」がつかないことがまれにある。
     * その場合も、同じ人の応募のどれかが次に変わったときの付け直しで正しくなる（design.md 2章）。
     */
    public static void refreshFlags(ApplicationRepository repository, Set<XId> xIds, Map<String, Application> changed) {
        for (XId xId : xIds) {
            Map<String, Application> group = new LinkedHashMap<>();
            for (Application stored : repository.findByXId(xId)) {
                group.put(stored.id(), changed.getOrDefault(stored.id(), stored));
            }
            // まだ保存していない応募（新しい応募、XのIDを直した応募）は保存先からは見つからないので足す
            for (Application application : changed.values()) {
                group.putIfAbsent(application.id(), application);
            }
            // XのIDを直して別の人になった応募は外す
            group.values().removeIf(application -> !application.xId().equals(xId));

            for (Application application : group.values()) {
                if (application.replaceFlags(ApplicantFlags.judge(application, group.values()))) {
                    changed.putIfAbsent(application.id(), application);
                }
            }
        }
    }

    /**
     * 配信用画面の「次の人へ」（仕様 7.2）。
     *
     * <p>いま配信用画面に出ている人（最後に分析中にした1人）を「分析済み」にし、「次に分析する順」（5.4）の先頭の人を「分析中」にする。
     * <ul>
     *   <li>「分析予定」の人は、配信者さんがすでに分析すると決めた人なので、印があってもそのまま選ぶ
     *   <li>「未着手」の人は、重複・条件外の印があれば飛ばす（同じ人を2回分析しないため）
     *   <li>抽選を使う設定のときは「未着手」の人を選ばない。未着手の人は抽選で選ぶため
     * </ul>
     *
     * @param includePending 「未着手」の人も選ぶか（抽選を使わない設定なら true）
     * @return 進めた後に配信用画面へ出す内容。次の人がいなければ空
     */
    public Optional<StreamView> advanceStream(boolean includePending) {
        synchronized (repository) {
            Instant now = clock.instant();
            Map<String, Application> changed = new LinkedHashMap<>();
            Set<XId> affected = new HashSet<>();
            Application current = currentOnStream();
            if (current != null) {
                current.changeStatusBySystem(ApplicationStatus.DONE, null, now, "次の人へ");
                changed.put(current.id(), current);
                affected.add(current.xId());
            }
            Application next = nextInQueue(includePending);
            if (next != null) {
                next.changeStatusBySystem(ApplicationStatus.ANALYZING, null, now, "次の人へ");
                changed.put(next.id(), next);
                affected.add(next.xId());
            }
            refreshFlags(repository, affected, changed);
            repository.saveAll(List.copyOf(changed.values()));
            return streamView();
        }
    }

    private Application nextInQueue(boolean includePending) {
        List<Application> scheduled = list(ApplicationFilter.statuses(ApplicationStatus.SCHEDULED), "queue");
        if (!scheduled.isEmpty()) {
            return scheduled.get(0);
        }
        if (!includePending) {
            return null;
        }
        for (Application pending : list(ApplicationFilter.statuses(ApplicationStatus.PENDING), "queue")) {
            if (!pending.hasBlockingFlag()) {
                return pending;
            }
        }
        return null;
    }

    /**
     * 配信用画面の内容（仕様 7.2）。「分析中」の人がいなければ空。
     * 分析中が複数いる場合は、最後に分析中にした人を出す。
     * 比較用の「前回」は、前回分析した応募（仕様 5.5）。
     * TODO(段階5): 配信用画面に「前回の分析日」も出す（仕様 7.2）。
     */
    public Optional<StreamView> streamView() {
        Application current = currentOnStream();
        if (current == null) {
            return Optional.empty();
        }
        Map<String, Object> previous = previousAnalyzed(current).map(ApplicationService::streamSafeAnswers).orElse(null);
        String displayName = current.anonymous() ? ANONYMOUS_DISPLAY_NAME : current.admiralName();
        return Optional.of(new StreamView(
                current.id(), displayName, current.simulatorUrl(), streamSafeAnswers(current), previous));
    }

    /** 配信用画面に出す人: 「分析中」の中で、最後にステータスを変えた人。いなければ null */
    private Application currentOnStream() {
        Application current = null;
        for (Application application : repository.findAll()) {
            if (application.status() != ApplicationStatus.ANALYZING) {
                continue;
            }
            // updatedAt（メモの変更でも変わる）ではなく、ステータスを変えた日時で選ぶ
            if (current == null || application.statusChangedAt().isAfter(current.statusChangedAt())) {
                current = application;
            }
        }
        return current;
    }

    /**
     * 配信に出してよい回答だけを取り出す（仕様 7.2）。
     * 「出してはいけない項目を消す」やり方だと、フォームに項目を足したときに配信へ漏れる恐れがあるため、
     * 「出してよい項目だけを選ぶ」やり方（許可リスト）にしている。
     * 名前は匿名希望のときに漏れないよう、answers には入れず displayName だけで出す。
     */
    private static Map<String, Object> streamSafeAnswers(Application application) {
        Map<String, Object> answers = new LinkedHashMap<>();
        for (String key : AnswerKeys.STREAM_VISIBLE) {
            if (application.answers().containsKey(key)) {
                answers.put(key, application.answers().get(key));
            }
        }
        return answers;
    }

    /** URLとして正しい形か（http/https で始まり、空白などを含まない）を確かめる。画面でリンクとして開くため */
    private static String requiredUrl(Map<String, Object> answers, String key) {
        return checkUrl(requiredText(answers, key), key);
    }

    private static String checkUrl(String text, String name) {
        try {
            URI uri = new URI(text);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null) {
                return text;
            }
        } catch (URISyntaxException e) {
            // 下の例外にまとめる
        }
        throw new InvalidValueException("URLの形式が正しくありません: " + name);
    }

    private static String requiredText(Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new InvalidValueException("必須の項目がありません: " + key);
        }
        return value.toString().strip();
    }
}
