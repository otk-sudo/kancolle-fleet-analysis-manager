package io.github.otksudo.fleetanalysis.domain.application;

import java.util.Map;

/**
 * 配信用画面に出す内容（仕様 7.2）。XのIDと課金額は含めない。
 *
 * @param applicationId 応募ID（画面の更新判定用。画面には出さない）
 * @param displayName   表示名。匿名希望なら「匿名提督」
 * @param simulatorUrl  制空権シミュレータのURL
 * @param answers       配信に出してよい回答
 * @param previous      前回の応募の回答（比較用）。なければ null
 */
public record StreamView(
        String applicationId,
        String displayName,
        String simulatorUrl,
        Map<String, Object> answers,
        Map<String, Object> previous) {
}
