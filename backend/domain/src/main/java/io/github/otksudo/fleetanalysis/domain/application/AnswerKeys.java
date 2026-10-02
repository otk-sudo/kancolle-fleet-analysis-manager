package io.github.otksudo.fleetanalysis.domain.application;

/**
 * フォーム回答の項目コード（gas/Code.gs の ITEM_CODES と同じ）。
 *
 * <p>回答は「項目コード → 回答」の Map で持つ。フォームの項目が増減しても、Mapなので古い応募もそのまま読める（仕様 10章）。
 * ここでは、業務ロジックが直接使う項目だけを定数にしている。
 */
public final class AnswerKeys {

    public static final String X_ID = "xId";
    public static final String ADMIRAL_NAME = "admiralName";
    /** 配信での名前の出し方。「匿名希望」なら匿名 */
    public static final String NAME_DISPLAY = "nameDisplay";
    public static final String SIMULATOR_URL = "simulatorUrl";
    public static final String MONTHLY_SPENDING = "monthlySpending";
    public static final String PURPOSE = "purpose";

    /** 匿名を希望するときの回答 */
    public static final String ANONYMOUS_ANSWER = "匿名希望";

    private AnswerKeys() {
    }
}
