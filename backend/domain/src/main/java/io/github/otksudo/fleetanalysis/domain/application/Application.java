package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 応募1件。
 *
 * <p>受付時に決まる情報（回答ID、提督名、受付日時など）は変えられないように final にし、
 * 運用中に変わる情報（ステータス、配信日、メモ、並び順、印など）だけを変更用のメソッドで変える。
 * こうしておくと「どこで何が変わるか」が追いやすい。
 *
 * <p>XのIDは、応募者が打ち間違えたときに直せるよう（仕様 5.6）、変更できる値にしている。
 *
 * <p>「版」（version）について: 2人が同時に同じ応募を変えたとき、後から保存した人の変更で先の人の変更を
 * 消してしまわないように（仕様 5.1）、応募ごとに版の番号を持たせている。保存先は「読み込んだときと版が同じなら保存し、
 * 版を1つ増やす」「版が違えば（誰かが先に保存していれば）断る」という動きをする。これを「楽観ロック」と呼ぶ。
 */
public class Application {

    private final String id;
    private final String submissionId;
    private final String admiralName;
    private final boolean anonymous;
    private final String simulatorUrl;
    private final String formVersion;
    private final Instant receivedAt;

    private XId xId;
    private Map<String, Object> answers;
    private List<Flag> flags;
    private ApplicationStatus status;
    /** 見送りの理由。「見送り」のときだけ値がある（仕様 5.6） */
    private SkipReason skipReason;
    private LocalDate streamDate;
    private String memo;
    /** 分析メモ（配信者が残す要点。仕様 5.5） */
    private String analysisMemo;
    /** 配信アーカイブのURL（仕様 5.5） */
    private String archiveUrl;
    /** 「次に分析する人」の並び順。小さいほど先。受付時は受付日時から作る（仕様 5.4） */
    private long position;
    /** 抽選で当選したか。落選補正のリセットに使う（仕様 6.3） */
    private boolean wonLottery;
    private Instant updatedAt;
    /** 最後にステータスを変えた日時。配信用画面で「最後に分析中にした人」を選ぶのに使う（メモの変更では変わらない） */
    private Instant statusChangedAt;
    /** 版。0 はまだ一度も保存していない新しい応募。保存するたびに1増える（クラスの説明を参照） */
    private long version;
    /**
     * まだ保存していない変更履歴。ステータスやXのIDを変えるとここに足され、保存先が応募と一緒に保存してから空にする。
     * 応募と履歴を別々に保存すると、片方だけ保存されて食い違うことがあるため、一緒に保存できるようにしている。
     */
    private final List<HistoryEntry> pendingHistory = new ArrayList<>();

    public Application(
            String id,
            String submissionId,
            XId xId,
            String admiralName,
            boolean anonymous,
            String simulatorUrl,
            String formVersion,
            Map<String, Object> answers,
            Instant receivedAt,
            List<Flag> flags) {
        this.id = Objects.requireNonNull(id, "id");
        this.submissionId = Objects.requireNonNull(submissionId, "submissionId");
        this.xId = Objects.requireNonNull(xId, "xId");
        this.admiralName = Objects.requireNonNull(admiralName, "admiralName");
        this.anonymous = anonymous;
        this.simulatorUrl = Objects.requireNonNull(simulatorUrl, "simulatorUrl");
        this.formVersion = Objects.requireNonNull(formVersion, "formVersion");
        // 外から渡されたMapを後で書き換えられても影響を受けないよう、コピーしてから変更不可にする
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.flags = List.copyOf(flags);
        this.status = ApplicationStatus.PENDING;
        this.position = receivedAt.toEpochMilli();
        this.updatedAt = receivedAt;
        this.statusChangedAt = receivedAt;
    }

    /**
     * 保存先（DynamoDB など）から読み込んだ値で応募を組み立て直す。
     *
     * <p>コンストラクタは「新しく受け付けた応募」を作るためのもので、ステータスなどは初期値になる。
     * 保存済みの応募を読み込むときは、運用中に変わった値（ステータス、配信日など）もそのまま戻す必要があるので、
     * こちらを使う。業務ロジックからは呼ばない（保存先の実装だけが使う）。
     *
     * @param state 運用中に変わる値をまとめたもの
     */
    public static Application restore(
            String id,
            String submissionId,
            XId xId,
            String admiralName,
            boolean anonymous,
            String simulatorUrl,
            String formVersion,
            Map<String, Object> answers,
            Instant receivedAt,
            List<Flag> flags,
            State state) {
        Application application = new Application(
                id, submissionId, xId, admiralName, anonymous, simulatorUrl, formVersion, answers, receivedAt, flags);
        application.status = Objects.requireNonNull(state.status(), "status");
        application.skipReason = state.skipReason();
        application.streamDate = state.streamDate();
        application.memo = state.memo();
        application.analysisMemo = state.analysisMemo();
        application.archiveUrl = state.archiveUrl();
        application.position = state.position();
        application.wonLottery = state.wonLottery();
        application.updatedAt = Objects.requireNonNull(state.updatedAt(), "updatedAt");
        application.statusChangedAt = Objects.requireNonNull(state.statusChangedAt(), "statusChangedAt");
        application.version = state.version();
        return application;
    }

    /**
     * 運用中に変わる値をまとめたもの（{@link #restore} で使う）。
     *
     * <p>{@code record} は「値を入れておくだけのクラス」を短く書くためのJavaの仕組み。
     * フィールド、コンストラクタ、値を取り出すメソッド（status() など）が自動で作られる。
     */
    public record State(
            ApplicationStatus status,
            SkipReason skipReason,
            LocalDate streamDate,
            String memo,
            String analysisMemo,
            String archiveUrl,
            long position,
            boolean wonLottery,
            Instant updatedAt,
            Instant statusChangedAt,
            long version) {
    }

    /** 今の値を {@link State} にまとめる（{@link #copy} と保存先の実装で使う）。 */
    public State state() {
        return new State(status, skipReason, streamDate, memo, analysisMemo, archiveUrl, position, wonLottery,
                updatedAt, statusChangedAt, version);
    }

    /**
     * 同じ中身の別のオブジェクトを作る。メモリ保存の実装が、DynamoDB と同じように
     * 「読み込むたびに別のオブジェクトを返す」ために使う（保存していない変更が、ほかの処理から見えないように）。
     */
    public Application copy() {
        return restore(id, submissionId, xId, admiralName, anonymous, simulatorUrl, formVersion, answers, receivedAt,
                flags, state());
    }

    /**
     * ステータスを手で変える（仕様 5.1 の表で許された組み合わせだけ）。許されなければ {@link ConflictException}。
     *
     * @param next       新しいステータス
     * @param skipReason 見送りの理由。「見送り」にするときは必須、それ以外は null
     * @param now        変更した日時
     */
    public void changeStatus(ApplicationStatus next, SkipReason skipReason, Instant now) {
        if (!status.canChangeTo(next)) {
            throw new ConflictException(
                    "「" + status.label() + "」から「" + next.label() + "」には変更できません");
        }
        applyStatus(next, skipReason, now, null);
    }

    /**
     * 仕組み（抽選、「次の人へ」、重複の解消など）がステータスを変える。手で変えるときの表（仕様 5.1）は使わない。
     *
     * <p>例: 抽選で外れた人を「落選」にするのは、手ではできないが抽選ではできる。
     *
     * @param note どの操作で変わったか（履歴に残す。例: "抽選"）
     */
    public void changeStatusBySystem(ApplicationStatus next, SkipReason skipReason, Instant now, String note) {
        applyStatus(next, skipReason, now, note);
    }

    private void applyStatus(ApplicationStatus next, SkipReason reason, Instant now, String note) {
        if (next == ApplicationStatus.SKIPPED && reason == null) {
            throw new InvalidValueException("見送りにするときは理由を選んでください");
        }
        if (next != ApplicationStatus.SKIPPED && reason != null) {
            throw new InvalidValueException("見送りの理由は、見送りにするときだけ選べます");
        }
        if (next == status) {
            if (next == ApplicationStatus.SKIPPED && reason != skipReason) {
                // ステータスはそのままで、見送りの理由だけを直す
                changeSkipReason(reason, now);
            }
            return;
        }
        String detail = reason == null ? null : "見送りの理由: " + reason.label();
        pendingHistory.add(new HistoryEntry(UUID.randomUUID().toString(), now, HistoryEntry.Kind.STATUS, status.code(), next.code(), joinNotes(note, detail)));
        this.status = next;
        this.skipReason = reason;
        this.updatedAt = now;
        this.statusChangedAt = now;
    }

    private static String joinNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + "、" + second;
    }

    /** 見送りの理由だけを直す（見送りのときだけ）。 */
    public void changeSkipReason(SkipReason reason, Instant now) {
        if (status != ApplicationStatus.SKIPPED) {
            throw new InvalidValueException("見送りの理由は、見送りの応募にだけ付けられます");
        }
        this.skipReason = Objects.requireNonNull(reason, "reason");
        this.updatedAt = now;
    }

    /**
     * XのIDを直す（仕様 5.6）。変更前のIDは履歴に残す。
     * 回答の中のXのIDも同じ値に直す（画面で回答を見たときに食い違わないように）。
     */
    public void changeXId(XId next, Instant now) {
        if (next.equals(xId)) {
            return;
        }
        pendingHistory.add(new HistoryEntry(UUID.randomUUID().toString(), now, HistoryEntry.Kind.X_ID, xId.value(), next.value(), null));
        Map<String, Object> newAnswers = new LinkedHashMap<>(answers);
        newAnswers.put(AnswerKeys.X_ID, next.value());
        this.answers = Collections.unmodifiableMap(newAnswers);
        this.xId = next;
        this.updatedAt = now;
    }

    /**
     * 印（重複・再応募・条件外）を付け直す（仕様 5.2）。
     *
     * @return 印が変わったら true（保存が必要かどうかの判断に使う）
     */
    public boolean replaceFlags(List<Flag> next) {
        if (flags.equals(next)) {
            return false;
        }
        this.flags = List.copyOf(next);
        return true;
    }

    public void changeStreamDate(LocalDate streamDate, Instant now) {
        this.streamDate = streamDate;
        this.updatedAt = now;
    }

    public void changeMemo(String memo, Instant now) {
        this.memo = memo;
        this.updatedAt = now;
    }

    public void changeAnalysisMemo(String analysisMemo, Instant now) {
        this.analysisMemo = analysisMemo;
        this.updatedAt = now;
    }

    /** @param archiveUrl 配信アーカイブのURL。null なら消す */
    public void changeArchiveUrl(String archiveUrl, Instant now) {
        this.archiveUrl = archiveUrl;
        this.updatedAt = now;
    }

    public void changePosition(long position) {
        this.position = position;
    }

    /**
     * 抽選で当選した印をつける。落選補正のリセットに使う（仕様 6.3）。
     * 当選後に手でステータスを戻しても印は残す（「一度当選した」事実は変わらないため）。
     */
    public void markWonLottery() {
        this.wonLottery = true;
    }

    /** まだ保存していない変更履歴（古い順）。 */
    public List<HistoryEntry> pendingHistory() {
        return List.copyOf(pendingHistory);
    }

    /**
     * 保存が終わったときに保存先が呼ぶ。版を1つ進め、保存した履歴を「まだ保存していない」一覧から外す。
     * 業務ロジックからは呼ばない。
     */
    public void markSaved() {
        this.version++;
        this.pendingHistory.clear();
    }

    public String id() {
        return id;
    }

    public String submissionId() {
        return submissionId;
    }

    public XId xId() {
        return xId;
    }

    public String admiralName() {
        return admiralName;
    }

    public boolean anonymous() {
        return anonymous;
    }

    public String simulatorUrl() {
        return simulatorUrl;
    }

    public String formVersion() {
        return formVersion;
    }

    public Map<String, Object> answers() {
        return answers;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public List<Flag> flags() {
        return flags;
    }

    public ApplicationStatus status() {
        return status;
    }

    public LocalDate streamDate() {
        return streamDate;
    }

    public String memo() {
        return memo;
    }

    public long position() {
        return position;
    }

    public boolean wonLottery() {
        return wonLottery;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public SkipReason skipReason() {
        return skipReason;
    }

    public String analysisMemo() {
        return analysisMemo;
    }

    public String archiveUrl() {
        return archiveUrl;
    }

    public long version() {
        return version;
    }

    /** 抽選・並べ替えの対象になる「印」がついているか（重複・条件外）。再応募は問題ないので含めない */
    public boolean hasBlockingFlag() {
        for (Flag flag : flags) {
            if (flag.type() == FlagType.DUPLICATE || flag.type() == FlagType.INELIGIBLE) {
                return true;
            }
        }
        return false;
    }
}
