package io.github.otksudo.fleetanalysis.domain.auth;

import io.github.otksudo.fleetanalysis.domain.ForbiddenException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 役割ごとにできること（仕様 2章、docs/design.md 3章の「権限」の列）。
 *
 * <ul>
 *   <li>運営: すべて
 *   <li>配信者: 削除依頼への対応とCSVの取り込み以外すべて
 *   <li>関係者: 見るだけ。配信者が許可した人は、配信の操作も
 * </ul>
 *
 * <p>配信者・運営は、二段階認証（MFA）を設定するまで何もできない（見ることもできない）。
 * 応募者の個人情報を扱うため（仕様 2章）。
 */
public final class AccessPolicy {

    private static final Set<Permission> STREAMER = Collections.unmodifiableSet(EnumSet.complementOf(
            EnumSet.of(Permission.DELETE_APPLICANT, Permission.IMPORT_CSV)));

    private AccessPolicy() {
    }

    /**
     * この人ができること。
     *
     * @param user           ログインしている人
     * @param streamOperator 関係者のとき、配信者から配信の操作を許可されているか（ほかの役割では使わない）
     */
    public static Set<Permission> permissionsOf(CurrentUser user, boolean streamOperator) {
        if (user.mfaRequired()) {
            return Set.of();
        }
        return switch (user.role()) {
            case ADMIN -> Collections.unmodifiableSet(EnumSet.allOf(Permission.class));
            case STREAMER -> STREAMER;
            case STAFF -> streamOperator
                    ? Collections.unmodifiableSet(EnumSet.of(Permission.VIEW, Permission.STREAM_OPERATION))
                    : Set.of(Permission.VIEW);
        };
    }

    /**
     * この人が permission を持っているか確かめ、持っていなければ {@link ForbiddenException}。
     * 何が足りないかがわかるメッセージにする（画面にそのまま出すため）。
     */
    public static void require(CurrentUser user, boolean streamOperator, Permission permission) {
        if (user.mfaRequired()) {
            throw new ForbiddenException(
                    "二段階認証（MFA）を設定するまで、" + user.role().label() + "の機能は使えません。先に二段階認証を設定してください");
        }
        if (!permissionsOf(user, streamOperator).contains(permission)) {
            throw new ForbiddenException(
                    "「" + permission.label() + "」をする権限がありません（" + user.role().label() + "）。必要なときは配信者か運営に相談してください");
        }
    }
}
