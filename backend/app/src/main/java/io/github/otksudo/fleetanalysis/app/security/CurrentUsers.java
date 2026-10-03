package io.github.otksudo.fleetanalysis.app.security;

import io.github.otksudo.fleetanalysis.domain.ForbiddenException;
import io.github.otksudo.fleetanalysis.domain.auth.AccessPolicy;
import io.github.otksudo.fleetanalysis.domain.auth.CurrentUser;
import io.github.otksudo.fleetanalysis.domain.auth.Permission;
import io.github.otksudo.fleetanalysis.domain.auth.Role;
import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorService;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * いまのリクエストを送ってきた人（ログインしている人）を取り出し、権限を確かめる。
 *
 * <p>Spring Security は、トークンを確かめたあと、その中身（{@link Jwt}）を「SecurityContext」という入れ物に入れておく。
 * ここではそれを読んで、domain の {@link CurrentUser} に直す。
 *
 * <p>使い方（コントローラーで）: {@code users.require(Permission.EDIT_APPLICATIONS)} と書くと、
 * 権限がなければ {@link ForbiddenException}（403）になり、そこで止まる。
 */
public class CurrentUsers {

    private final StreamOperatorService streamOperators;

    public CurrentUsers(StreamOperatorService streamOperators) {
        this.streamOperators = streamOperators;
    }

    /** ログインしている人。ログインしていなければ（ふつうは Spring Security が先に401にするので来ない）例外 */
    public CurrentUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("ログインしていません。ログインし直してください");
        }
        return fromToken(jwt);
    }

    /** トークンの中身から、ログインしている人を作る */
    static CurrentUser fromToken(Jwt jwt) {
        String id = jwt.getSubject();
        String name = jwt.getClaimAsString(TokenClaims.NAME);
        Role role = roleOf(jwt.getClaimAsStringList(TokenClaims.GROUPS));
        // TODO(段階4): Cognito のときの「二段階認証を設定済みか」の確かめ方を、公式ドキュメントで調べて決める（未確認）
        boolean mfa = Boolean.TRUE.equals(jwt.getClaimAsBoolean(TokenClaims.DEV_MFA));
        return new CurrentUser(id, name != null && !name.isBlank() ? name : id, role, mfa);
    }

    /**
     * グループの一覧から役割を決める。複数のグループに入っていたら、いちばん強い役割（運営 → 配信者 → 関係者の順）にする。
     * どのグループにも入っていない人は、ツールを使えない
     */
    static Role roleOf(List<String> groups) {
        if (groups != null) {
            for (Role role : List.of(Role.ADMIN, Role.STREAMER, Role.STAFF)) {
                if (groups.contains(role.code())) {
                    return role;
                }
            }
        }
        throw new ForbiddenException("役割（運営・配信者・関係者）が設定されていません。運営に相談してください");
    }

    /** 関係者で、配信の操作を許可されているか */
    public boolean isStreamOperator(CurrentUser user) {
        return user.role() == Role.STAFF && streamOperators.isOperator(user.id());
    }

    /** この人ができること */
    public Set<Permission> permissions(CurrentUser user) {
        return AccessPolicy.permissionsOf(user, isStreamOperator(user));
    }

    /** ログインしている人が permission を持っているか確かめ、持っていなければ403。確かめた人を返す */
    public CurrentUser require(Permission permission) {
        CurrentUser user = current();
        AccessPolicy.require(user, isStreamOperator(user), permission);
        return user;
    }
}
