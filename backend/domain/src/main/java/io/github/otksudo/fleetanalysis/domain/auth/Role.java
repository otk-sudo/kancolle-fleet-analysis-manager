package io.github.otksudo.fleetanalysis.domain.auth;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;

/**
 * 役割（仕様 2章）。Cognito では「グループ」として持たせる。
 */
public enum Role {
    /** 運営: 開発者などの管理者。すべての操作ができる */
    ADMIN("admin", "運営"),
    /** 配信者: 分析を行う配信者さん */
    STREAMER("streamer", "配信者"),
    /** 関係者: 配信者さんの手伝いをする人 */
    STAFF("staff", "関係者");

    private final String code;
    private final String label;

    Role(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** APIやトークンでのコード（例: "streamer"）。Cognito のグループ名もこれにする */
    public String code() {
        return code;
    }

    /** 画面やメッセージに出す名前（例: 「配信者」） */
    public String label() {
        return label;
    }

    /**
     * 二段階認証（MFA）を設定しないと管理機能を使えない役割か。
     * 配信者と運営は、応募者の個人情報を扱い、削除もできるため必須にする（仕様 2章）
     */
    public boolean requiresMfa() {
        return this == ADMIN || this == STREAMER;
    }

    public static Role fromCode(String code) {
        for (Role role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new InvalidValueException("知らない役割です: " + code);
    }
}
