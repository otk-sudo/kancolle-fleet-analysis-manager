package io.github.otksudo.fleetanalysis.domain.lottery;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;

/** 抽選のしかた（仕様 6.2）。 */
public enum LotteryMode {
    /** まとめ抽選: 管理画面で当選人数を指定して一度に抽選。当選は「分析予定」、外れは「落選」 */
    BULK("bulk"),
    /** 配信中の抽選: 配信用画面で1人だけ抽選。当選は「分析中」、外れた人は「未着手」のまま */
    LIVE("live");

    private final String code;

    LotteryMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** APIのコード（例: "bulk"）から探す。 */
    public static LotteryMode fromCode(String code) {
        for (LotteryMode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new InvalidValueException("抽選のしかたのコードが正しくありません: " + code);
    }
}
