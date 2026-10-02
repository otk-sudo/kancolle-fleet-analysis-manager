package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 応募1件。
 *
 * <p>受付時に決まる情報（XのID、回答など）は変えられないように final にし、
 * 運用中に変わる情報（ステータス、配信日、メモ、並び順）だけを変更用のメソッドで変える。
 * こうしておくと「どこで何が変わるか」が追いやすい。
 */
public class Application {

    private final String id;
    private final String submissionId;
    private final XId xId;
    private final String admiralName;
    private final boolean anonymous;
    private final String simulatorUrl;
    private final String formVersion;
    private final Map<String, Object> answers;
    private final Instant receivedAt;
    private final List<Flag> flags;

    private ApplicationStatus status;
    private LocalDate streamDate;
    private String memo;
    /** 「次に分析する人」の並び順。小さいほど先。受付時は受付日時から作る（仕様 5.4） */
    private long position;
    /** 抽選で当選したか。落選補正のリセットに使う（仕様 6.3） */
    private boolean wonLottery;
    private Instant updatedAt;

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
    }

    /**
     * ステータスを変える。変えてはいけない組み合わせなら {@link ConflictException}。
     *
     * @param next 新しいステータス
     * @param now  変更した日時
     */
    public void changeStatus(ApplicationStatus next, Instant now) {
        if (!status.canChangeTo(next)) {
            throw new ConflictException(
                    "「" + status.label() + "」から「" + next.label() + "」には変更できません");
        }
        this.status = next;
        this.updatedAt = now;
    }

    public void changeStreamDate(LocalDate streamDate, Instant now) {
        this.streamDate = streamDate;
        this.updatedAt = now;
    }

    public void changeMemo(String memo, Instant now) {
        this.memo = memo;
        this.updatedAt = now;
    }

    public void changePosition(long position) {
        this.position = position;
    }

    public void markWonLottery() {
        this.wonLottery = true;
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
