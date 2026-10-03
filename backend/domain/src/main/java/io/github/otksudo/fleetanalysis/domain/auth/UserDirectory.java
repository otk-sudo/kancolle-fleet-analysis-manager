package io.github.otksudo.fleetanalysis.domain.auth;

import java.util.List;
import java.util.Optional;

/**
 * ツールを使う人（運営・配信者・関係者）の名簿の約束。
 *
 * <p>本番では Cognito のユーザープールが名簿になる（段階4で作る）。手元の仮ログインでは、決まった試し用の人を返す。
 */
public interface UserDirectory {

    /** 名簿の1人分 */
    record Entry(String id, String displayName, Role role) {
    }

    List<Entry> findAll();

    Optional<Entry> find(String id);
}
