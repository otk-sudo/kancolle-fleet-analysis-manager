package io.github.otksudo.fleetanalysis.domain.auth;

import java.util.Set;

/**
 * 関係者への「配信の操作」の許可の保存先の約束（仕様 2章）。実際の保存先は infra が決める。
 * 初期値は「許可しない」なので、許可した人のユーザーIDだけを保存する。
 */
public interface StreamOperatorRepository {

    /** 許可されているユーザーIDの一覧 */
    Set<String> findAll();

    /** このユーザーが許可されているか */
    boolean isOperator(String userId);

    /** 許可する（すでに許可していれば何もしない） */
    void grant(String userId, String grantedBy);

    /** 取り消す（許可していなければ何もしない） */
    void revoke(String userId);
}
