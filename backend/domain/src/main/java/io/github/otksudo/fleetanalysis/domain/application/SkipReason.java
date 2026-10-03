package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;

/**
 * 見送りの理由（仕様 5.6）。「見送り」にするときは、必ずどれかを選ぶ。
 *
 * <p>理由を残しておくと、あとから「なぜ分析しなかったのか」を確かめられる。
 * 「その他」の詳しい中身は、応募のメモに書いてもらう。
 */
public enum SkipReason {
    /** 応募者が内容を直して送り直したので、古いほうを見送る（重複の解消で自動的に付く） */
    RESUBMITTED("resubmitted", "重複（再送のため）"),
    INELIGIBLE("ineligible", "条件外"),
    WITHDRAWN("withdrawn", "本人の取り下げ"),
    OTHER("other", "その他");

    private final String code;
    private final String label;

    SkipReason(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** APIのコード（例: "withdrawn"）から探す。 */
    public static SkipReason fromCode(String code) {
        for (SkipReason value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new InvalidValueException("見送りの理由のコードが正しくありません: " + code);
    }
}
