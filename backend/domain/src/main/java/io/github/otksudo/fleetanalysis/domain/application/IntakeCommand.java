package io.github.otksudo.fleetanalysis.domain.application;

import java.time.Instant;
import java.util.Map;

/**
 * フォームから届いた応募1件（受付処理への入力）。
 *
 * @param submissionId フォームの回答ID
 * @param submittedAt  回答日時
 * @param formVersion  フォームの版
 * @param answers      項目コード → 回答
 */
public record IntakeCommand(String submissionId, Instant submittedAt, String formVersion, Map<String, Object> answers) {
}
