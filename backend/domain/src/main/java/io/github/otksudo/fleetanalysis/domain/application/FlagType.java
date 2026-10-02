package io.github.otksudo.fleetanalysis.domain.application;

/** 応募につける印の種類（仕様 5.2、5.3）。 */
public enum FlagType {
    /** 同じXのIDで、まだ終わっていない応募がすでにある */
    DUPLICATE("duplicate"),
    /** 同じXのIDで、過去に終わった応募がある */
    REAPPLY("reapply"),
    /** 条件外（段階7で条件ルールを実装） */
    INELIGIBLE("ineligible");

    private final String code;

    FlagType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
