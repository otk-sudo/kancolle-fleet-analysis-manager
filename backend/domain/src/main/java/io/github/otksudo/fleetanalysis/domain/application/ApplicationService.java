package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import io.github.otksudo.fleetanalysis.domain.XId;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        // TODO(段階1): DynamoDBでは「同じIDがなければ書き込む」条件付き書き込みにする
        synchronized (repository) {
            return submitLocked(command);
        }
    }

    private Application submitLocked(IntakeCommand command) {
        Optional<Application> existing = repository.findBySubmissionId(command.submissionId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<String, Object> answers = command.answers();
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
                judgeFlags(xId));
        repository.save(application);
        return application;
    }

    /**
     * 重複・再応募の印を決める（仕様 5.2）。自動で見送りにはせず、印をつけるだけ。
     */
    private List<Flag> judgeFlags(XId xId) {
        List<Flag> flags = new ArrayList<>();
        List<Application> sameApplicant = repository.findByXId(xId);

        for (Application other : sameApplicant) {
            if (other.status().isOpen()) {
                flags.add(new Flag(FlagType.DUPLICATE, "同じXのIDの応募が「" + other.status().label() + "」で残っています", other.id()));
                break;
            }
        }
        for (Application other : sameApplicant) {
            if (!other.status().isOpen()) {
                flags.add(new Flag(FlagType.REAPPLY, "過去に応募があります（" + other.status().label() + "）", other.id()));
                break;
            }
        }
        return flags;
    }

    /**
     * 応募の一覧（仕様 5.4、7.1）。
     *
     * @param statuses 絞り込むステータス。空なら全部
     * @param purpose  分析してほしい目的で絞り込む。null なら全部
     * @param flag     印で絞り込む。null なら全部
     * @param order    "queue"（次に分析する順）または "received"（受付順）
     */
    public List<Application> list(List<ApplicationStatus> statuses, String purpose, FlagType flag, String order) {
        List<Application> result = new ArrayList<>();
        for (Application application : repository.findAll()) {
            if (!statuses.isEmpty() && !statuses.contains(application.status())) {
                continue;
            }
            if (purpose != null && !purpose.equals(application.answers().get(AnswerKeys.PURPOSE))) {
                continue;
            }
            if (flag != null && !hasFlag(application, flag)) {
                continue;
            }
            result.add(application);
        }

        if ("received".equals(order)) {
            result.sort(Comparator.comparing(Application::receivedAt));
        } else {
            result.sort(QUEUE_ORDER);
        }
        return result;
    }

    /**
     * 「次に分析する順」の並べ方（仕様 5.4）。
     * 分析中 → 分析予定 → 未着手 → それ以外 の順に並べ、同じグループの中は並び順（position）の小さい順。
     */
    private static final Comparator<Application> QUEUE_ORDER =
            Comparator.comparingInt((Application a) -> queueGroup(a.status()))
                    .thenComparingLong(Application::position)
                    .thenComparing(Application::receivedAt);

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

    /**
     * ステータス・配信日・メモを変える。null の項目は変えない。
     *
     * @param clearStreamDate true なら配信日を消す（null の「変えない」と区別するため）
     */
    public Application update(String id, ApplicationStatus status, LocalDate streamDate, boolean clearStreamDate, String memo) {
        synchronized (repository) {
            return updateLocked(id, status, streamDate, clearStreamDate, memo);
        }
    }

    private Application updateLocked(String id, ApplicationStatus status, LocalDate streamDate, boolean clearStreamDate, String memo) {
        Application application = get(id);
        Instant now = clock.instant();
        if (status != null && status != application.status()) {
            application.changeStatus(status, now);
            placeAtEndOfGroup(repository, application);
        }
        if (clearStreamDate) {
            application.changeStreamDate(null, now);
        } else if (streamDate != null) {
            application.changeStreamDate(streamDate, now);
        }
        if (memo != null) {
            application.changeMemo(memo, now);
        }
        repository.save(application);
        return application;
    }

    /**
     * 「次に分析する順」を手で並べ替える（仕様 5.4）。
     *
     * <p>同じグループ（分析予定どうし、未着手どうし）の中で、{@code afterId} の直後に移動する。
     * {@code afterId} が null ならグループの先頭へ。
     * 試作では、グループの全員の並び順を 1000, 2000, 3000... と振り直すわかりやすい方法をとる。
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
        if (group != ApplicationStatus.PENDING && group != ApplicationStatus.SCHEDULED) {
            throw new InvalidValueException("並べ替えできるのは「未着手」と「分析予定」の応募だけです");
        }

        List<Application> sameGroup = list(List.of(group), null, null, "queue");
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
        sameGroup.add(insertAt, target);

        for (int i = 0; i < sameGroup.size(); i++) {
            Application application = sameGroup.get(i);
            application.changePosition((i + 1) * 1000L);
            repository.save(application);
        }
    }

    /** 同じ応募者の応募履歴（新しい順）。仕様 5.5 */
    public List<Application> history(XId xId) {
        List<Application> result = new ArrayList<>(repository.findByXId(xId));
        result.sort(Comparator.comparing(Application::receivedAt).reversed());
        return result;
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
     * <p>並び順（position）はグループの中だけで意味を持つ。手で並べ替えたグループは 1000, 2000... と振り直されているので、
     * 別のグループから移ってきた応募の並び順をそのままにすると、先頭など思わぬ場所に入ってしまう。
     * 抽選で当選して「分析予定」になったときにも使うため、static にして LotteryService からも呼べるようにしている。
     */
    public static void placeAtEndOfGroup(ApplicationRepository repository, Application application) {
        ApplicationStatus group = application.status();
        if (group != ApplicationStatus.PENDING && group != ApplicationStatus.SCHEDULED) {
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
     * 配信用画面の「次の人へ」（仕様 7.2）。抽選を使わずに配信を進めるときに使う。
     *
     * <p>いま配信用画面に出ている人（分析中）を「分析済み」にし、「次に分析する順」（5.4）の先頭の人を「分析中」にする。
     * 重複・条件外の印がある人は、同じ人を2回分析しないよう飛ばす（印の内容は一覧で確かめてから手で進められる）。
     *
     * @return 進めた後に配信用画面へ出す内容。待っている人がいなければ空
     */
    public Optional<StreamView> advanceStream() {
        synchronized (repository) {
            Instant now = clock.instant();
            Application current = currentOnStream();
            if (current != null) {
                current.changeStatus(ApplicationStatus.DONE, now);
                repository.save(current);
            }
            for (Application candidate : list(List.of(ApplicationStatus.SCHEDULED, ApplicationStatus.PENDING), null, null, "queue")) {
                if (candidate.hasBlockingFlag()) {
                    continue;
                }
                candidate.changeStatus(ApplicationStatus.ANALYZING, now);
                repository.save(candidate);
                break;
            }
            return streamView();
        }
    }

    /**
     * 配信用画面の内容（仕様 7.2）。「分析中」の人がいなければ空。
     * 分析中が複数いる場合は、最後に分析中にした人を出す。
     */
    public Optional<StreamView> streamView() {
        Application current = currentOnStream();
        if (current == null) {
            return Optional.empty();
        }

        Map<String, Object> previous = null;
        for (Application past : history(current.xId())) {
            if (past.receivedAt().isBefore(current.receivedAt())) {
                previous = streamSafeAnswers(past);
                break; // history は新しい順なので、最初に見つかったものが前回
            }
        }

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
        String text = requiredText(answers, key);
        try {
            URI uri = new URI(text);
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                return text;
            }
        } catch (URISyntaxException e) {
            // 下の例外にまとめる
        }
        throw new InvalidValueException("URLの形式が正しくありません: " + key);
    }

    private static String requiredText(Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new InvalidValueException("必須の項目がありません: " + key);
        }
        return value.toString().strip();
    }
}
