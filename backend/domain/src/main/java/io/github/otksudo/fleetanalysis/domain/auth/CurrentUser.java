package io.github.otksudo.fleetanalysis.domain.auth;

import java.util.Objects;

/**
 * ログインしている人。
 *
 * @param id             ユーザーID（Cognito では「sub」という、変わらないID）
 * @param displayName    表示名。履歴や抽選記録の「誰が」に残す
 * @param role           役割
 * @param mfaConfigured  二段階認証（MFA）を設定済みか
 */
public record CurrentUser(String id, String displayName, Role role, boolean mfaConfigured) {

    public CurrentUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
    }

    /** MFA を設定するまで管理機能を使えない状態か（配信者・運営で、まだ設定していない） */
    public boolean mfaRequired() {
        return role.requiresMfa() && !mfaConfigured;
    }
}
