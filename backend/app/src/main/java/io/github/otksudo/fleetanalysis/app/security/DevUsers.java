package io.github.otksudo.fleetanalysis.app.security;

import io.github.otksudo.fleetanalysis.domain.auth.Role;
import io.github.otksudo.fleetanalysis.domain.auth.UserDirectory;
import java.util.List;
import java.util.Optional;

/**
 * 手元の仮ログインで選べる、試し用の人たち。名簿（{@link UserDirectory}）としても使う。
 *
 * <p>本番では Cognito のユーザープールが名簿になる（段階4）。
 * 「二段階認証を設定していない配信者」もいるので、MFA が必要な画面も手元で試せる。
 */
public final class DevUsers implements UserDirectory {

    /** 試し用の1人分 */
    public record DevUser(String id, String displayName, Role role, boolean mfaConfigured) {
    }

    static final List<DevUser> USERS = List.of(
            new DevUser("dev-admin", "運営の試しユーザー", Role.ADMIN, true),
            new DevUser("dev-streamer", "配信者の試しユーザー", Role.STREAMER, true),
            new DevUser("dev-streamer-no-mfa", "配信者の試しユーザー（二段階認証なし）", Role.STREAMER, false),
            new DevUser("dev-staff-a", "関係者Aの試しユーザー", Role.STAFF, true),
            new DevUser("dev-staff-b", "関係者Bの試しユーザー", Role.STAFF, true));

    public List<DevUser> users() {
        return USERS;
    }

    public Optional<DevUser> user(String id) {
        return USERS.stream().filter(user -> user.id().equals(id)).findFirst();
    }

    @Override
    public List<Entry> findAll() {
        return USERS.stream().map(user -> new Entry(user.id(), user.displayName(), user.role())).toList();
    }

    @Override
    public Optional<Entry> find(String id) {
        return user(id).map(user -> new Entry(user.id(), user.displayName(), user.role()));
    }
}
