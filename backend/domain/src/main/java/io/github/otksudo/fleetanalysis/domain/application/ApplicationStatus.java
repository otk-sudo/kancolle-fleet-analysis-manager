package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import java.util.Set;

/**
 * 応募の進捗ステータス（仕様 5.1）。
 *
 * <p>{@code enum}（列挙型）は「決まった値のどれか」を表すJavaの仕組み。文字列で持つより打ち間違いに強い。
 * 各値に、APIで使うコード（英語）と画面に出す名前（日本語）を持たせている。
 *
 * <p>TODO(段階7): 仕様では「ステータスは設定で追加できる」ため、設定データから読む形に置き換える。
 */
public enum ApplicationStatus {
    PENDING("pending", "未着手"),
    SCHEDULED("scheduled", "分析予定"),
    ANALYZING("analyzing", "分析中"),
    DONE("done", "分析済み"),
    SKIPPED("skipped", "見送り"),
    LOST("lost", "落選");

    private final String code;
    private final String label;

    ApplicationStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** まだ分析が終わっていない（重複判定の対象になる）ステータスか。仕様 5.2 */
    public boolean isOpen() {
        return this == PENDING || this == SCHEDULED || this == ANALYZING;
    }

    /**
     * このステータスから {@code next} へ変更してよいか（仕様 5.1 のステータス遷移）。
     *
     * <p>うっかり「分析済み」を「落選」にしてしまう、といった誤操作を防ぐために、変えてよい組み合わせを決めておく。
     */
    public boolean canChangeTo(ApplicationStatus next) {
        if (this == next) {
            return true;
        }
        Set<ApplicationStatus> allowed = switch (this) {
            case PENDING -> Set.of(SCHEDULED, ANALYZING, SKIPPED, LOST);
            case SCHEDULED -> Set.of(PENDING, ANALYZING, SKIPPED);
            case ANALYZING -> Set.of(PENDING, SCHEDULED, DONE);
            case DONE -> Set.of(ANALYZING); // 間違えて「分析済み」にしたときに戻せるように
            case SKIPPED, LOST -> Set.of(PENDING); // 判断を取り消して未着手に戻す
        };
        return allowed.contains(next);
    }

    /** APIのコード（例: "pending"）からステータスを探す。 */
    public static ApplicationStatus fromCode(String code) {
        for (ApplicationStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new NotFoundException("ステータスが見つかりません: " + code);
    }
}
