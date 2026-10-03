package io.github.otksudo.fleetanalysis.domain.application;

import java.time.Instant;
import java.util.Objects;

/**
 * 応募の変更履歴1件（仕様 5.1「いつ・誰が・何から何へ」、5.6「変更前のXのIDを残す」）。
 *
 * <p>履歴は応募と一緒に保存する（{@link Application#pendingHistory()} を参照）。
 * あとから書き換えることはないので、変更できない record にしている。
 *
 * @param id     履歴のID（同じ時刻の変更を見分けるため）
 * @param at     変更した日時
 * @param actor  変更した人
 * @param kind   何を変えたか
 * @param from   変更前（ステータスのコード、またはXのID）
 * @param to     変更後
 * @param note   補足（見送りの理由、どの操作で変わったか、など）。なければ null
 */
public record HistoryEntry(String id, Instant at, String actor, Kind kind, String from, String to, String note) {

    public HistoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** 何を変えたか */
    public enum Kind {
        /** ステータスの変更 */
        STATUS("status"),
        /** XのIDの修正 */
        X_ID("xId");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Kind fromCode(String code) {
            for (Kind value : values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("履歴の種類のコードが正しくありません: " + code);
        }
    }
}
