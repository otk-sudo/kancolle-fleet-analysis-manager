package io.github.otksudo.fleetanalysis.domain.application;

import java.time.LocalDate;

/**
 * 応募の詳細画面から送られてくる「変えたい項目」をまとめたもの（{@link ApplicationService#update}）。
 *
 * <p>null の項目は「変えない」。項目が多く、引数を並べると順番を間違えやすいので、1つの record にまとめている。
 * {@code withStatus} などのメソッドは、その項目だけを入れ替えた新しい値を返す（元の値は変わらない）。
 * 例: {@code ApplicationChanges.none().withStatus(ApplicationStatus.SKIPPED).withSkipReason(SkipReason.WITHDRAWN)}
 *
 * @param status          新しいステータス
 * @param skipReason      見送りの理由（見送りにするときは必須）
 * @param streamDate      配信日
 * @param clearStreamDate true なら配信日を消す（null の「変えない」と区別するため）
 * @param memo            メモ
 * @param analysisMemo    分析メモ
 * @param archiveUrl      配信アーカイブのURL。空文字なら消す
 * @param xId             XのIDの修正（入力のまま。正規化はサービスで行う）
 */
public record ApplicationChanges(
        ApplicationStatus status,
        SkipReason skipReason,
        LocalDate streamDate,
        boolean clearStreamDate,
        String memo,
        String analysisMemo,
        String archiveUrl,
        String xId) {

    /** 何も変えない */
    public static ApplicationChanges none() {
        return new ApplicationChanges(null, null, null, false, null, null, null, null);
    }

    public ApplicationChanges withStatus(ApplicationStatus value) {
        return new ApplicationChanges(value, skipReason, streamDate, clearStreamDate, memo, analysisMemo, archiveUrl, xId);
    }

    public ApplicationChanges withSkipReason(SkipReason value) {
        return new ApplicationChanges(status, value, streamDate, clearStreamDate, memo, analysisMemo, archiveUrl, xId);
    }

    public ApplicationChanges withStreamDate(LocalDate value) {
        return new ApplicationChanges(status, skipReason, value, clearStreamDate, memo, analysisMemo, archiveUrl, xId);
    }

    public ApplicationChanges withClearStreamDate() {
        return new ApplicationChanges(status, skipReason, streamDate, true, memo, analysisMemo, archiveUrl, xId);
    }

    public ApplicationChanges withMemo(String value) {
        return new ApplicationChanges(status, skipReason, streamDate, clearStreamDate, value, analysisMemo, archiveUrl, xId);
    }

    public ApplicationChanges withAnalysisMemo(String value) {
        return new ApplicationChanges(status, skipReason, streamDate, clearStreamDate, memo, value, archiveUrl, xId);
    }

    public ApplicationChanges withArchiveUrl(String value) {
        return new ApplicationChanges(status, skipReason, streamDate, clearStreamDate, memo, analysisMemo, value, xId);
    }

    public ApplicationChanges withXId(String value) {
        return new ApplicationChanges(status, skipReason, streamDate, clearStreamDate, memo, analysisMemo, archiveUrl, value);
    }
}
