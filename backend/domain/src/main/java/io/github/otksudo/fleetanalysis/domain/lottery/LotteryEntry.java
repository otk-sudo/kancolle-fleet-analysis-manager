package io.github.otksudo.fleetanalysis.domain.lottery;

import java.util.Objects;

/** 抽選の対象者1人分。 */
public record LotteryEntry(String applicationId, double weight) {

    public LotteryEntry {
        Objects.requireNonNull(applicationId, "applicationId");
        if (!(weight > 0)) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
    }
}
