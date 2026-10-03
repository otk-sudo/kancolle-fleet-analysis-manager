package io.github.otksudo.fleetanalysis.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.ForbiddenException;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** 役割ごとにできること（仕様 2章）のテスト。 */
class AccessPolicyTest {

    private static final CurrentUser ADMIN = new CurrentUser("a", "運営", Role.ADMIN, true);
    private static final CurrentUser STREAMER = new CurrentUser("s", "配信者", Role.STREAMER, true);
    private static final CurrentUser STREAMER_NO_MFA = new CurrentUser("s2", "配信者2", Role.STREAMER, false);
    private static final CurrentUser STAFF = new CurrentUser("t", "関係者", Role.STAFF, false);

    @Test
    void 運営はすべてできる() {
        assertThat(AccessPolicy.permissionsOf(ADMIN, false)).isEqualTo(EnumSet.allOf(Permission.class));
    }

    @Test
    void 配信者は削除依頼への対応とCSVの取り込み以外ができる() {
        assertThat(AccessPolicy.permissionsOf(STREAMER, false))
                .contains(Permission.VIEW, Permission.EDIT_APPLICATIONS, Permission.STREAM_OPERATION,
                        Permission.BULK_LOTTERY, Permission.EDIT_SETTINGS, Permission.MANAGE_STREAM_OPERATORS,
                        Permission.MANAGE_USERS)
                .doesNotContain(Permission.DELETE_APPLICANT, Permission.IMPORT_CSV);
    }

    @Test
    void 関係者は見るだけで_許可されると配信の操作もできる() {
        assertThat(AccessPolicy.permissionsOf(STAFF, false)).containsExactly(Permission.VIEW);
        assertThat(AccessPolicy.permissionsOf(STAFF, true))
                .containsExactlyInAnyOrder(Permission.VIEW, Permission.STREAM_OPERATION);
    }

    @Test
    void 関係者は二段階認証がなくても使える() {
        // MFA が必須なのは配信者と運営だけ（仕様 2章）
        assertThat(STAFF.mfaRequired()).isFalse();
    }

    @Test
    void 二段階認証を設定していない配信者は何もできない() {
        assertThat(STREAMER_NO_MFA.mfaRequired()).isTrue();
        assertThat(AccessPolicy.permissionsOf(STREAMER_NO_MFA, false)).isEmpty();
        assertThatThrownBy(() -> AccessPolicy.require(STREAMER_NO_MFA, false, Permission.VIEW))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("二段階認証");
    }

    @Test
    void 権限がなければ何が足りないかを伝える() {
        assertThatThrownBy(() -> AccessPolicy.require(STAFF, false, Permission.EDIT_APPLICATIONS))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("応募を変更する");
        AccessPolicy.require(STAFF, true, Permission.STREAM_OPERATION); // 許可されていれば例外にならない
    }
}
