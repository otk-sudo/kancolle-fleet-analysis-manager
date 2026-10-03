package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;

/** 応募につける印の種類（仕様 5.2、5.3）。 */
public enum FlagType {
    /** 同じXのIDで、まだ終わっていない応募がすでにある */
    DUPLICATE("duplicate"),
    /** 同じXのIDで、過去に終わった応募がある */
    REAPPLY("reapply"),
    /** 条件外（段階9で条件ルールを実装） */
    INELIGIBLE("ineligible");

    private final String code;

    FlagType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** APIのコード（例: "duplicate"）から探す。 */
    public static FlagType fromCode(String code) {
        for (FlagType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new InvalidValueException("印の種類のコードが正しくありません: " + code);
    }
}
