package io.github.otksudo.fleetanalysis.domain.application;

import java.util.List;

/**
 * 応募一覧の絞り込み・検索の条件（仕様 7.1）。null や空の条件は「絞り込まない」。
 *
 * @param statuses      ステータス。空なら全部
 * @param purpose       分析してほしい目的
 * @param rankingEffort 戦果への取り組み
 * @param flag          印の種類
 * @param keyword       提督名・XのIDの一部（大文字・小文字、全角・半角、先頭の@は区別しない）
 */
public record ApplicationFilter(
        List<ApplicationStatus> statuses,
        String purpose,
        String rankingEffort,
        FlagType flag,
        String keyword) {

    public ApplicationFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }

    /** 絞り込まない（全部） */
    public static ApplicationFilter all() {
        return new ApplicationFilter(List.of(), null, null, null, null);
    }

    /** ステータスだけで絞り込む */
    public static ApplicationFilter statuses(ApplicationStatus... statuses) {
        return new ApplicationFilter(List.of(statuses), null, null, null, null);
    }
}
