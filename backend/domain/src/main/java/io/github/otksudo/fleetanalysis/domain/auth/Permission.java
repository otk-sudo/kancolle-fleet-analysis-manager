package io.github.otksudo.fleetanalysis.domain.auth;

/**
 * できること（権限）。APIの各操作は、このどれかを持っている人だけが行える。
 * どの役割がどれを持つかは {@link AccessPolicy} で決める。
 */
public enum Permission {
    /** 応募の一覧・詳細・履歴・抽選記録・設定を見る */
    VIEW("view", "応募を見る"),
    /** 応募のステータス・並べ替え・メモ・XのIDなどを変える */
    EDIT_APPLICATIONS("editApplications", "応募を変更する"),
    /** 配信の操作（次の人へ、配信中の抽選など。仕様 7.3） */
    STREAM_OPERATION("streamOperation", "配信の操作"),
    /** まとめ抽選 */
    BULK_LOTTERY("bulkLottery", "まとめ抽選"),
    /** 設定（抽選の設定など）を変える */
    EDIT_SETTINGS("editSettings", "設定を変更する"),
    /** 関係者に配信の操作を許可する・取り消す */
    MANAGE_STREAM_OPERATORS("manageStreamOperators", "配信の操作の許可"),
    /** ユーザーの追加・削除（配信者は関係者だけ。段階7） */
    MANAGE_USERS("manageUsers", "ユーザー管理"),
    /** 削除依頼への対応（応募者のデータを消す） */
    DELETE_APPLICANT("deleteApplicant", "削除依頼への対応"),
    /** 旧スプレッドシートのCSV取り込み */
    IMPORT_CSV("importCsv", "CSVの取り込み");

    private final String code;
    private final String label;

    Permission(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
