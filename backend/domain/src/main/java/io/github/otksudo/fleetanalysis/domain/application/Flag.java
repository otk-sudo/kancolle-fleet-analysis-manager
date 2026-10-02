package io.github.otksudo.fleetanalysis.domain.application;

import java.util.Objects;

/**
 * 応募につける印（重複・再応募・条件外）。
 *
 * @param type                 印の種類
 * @param reason               画面に出す理由
 * @param relatedApplicationId 関係する別の応募のID（重複相手など）。なければ null
 */
public record Flag(FlagType type, String reason, String relatedApplicationId) {

    public Flag {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }
}
