package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.otksudo.fleetanalysis.domain.lottery.LotteryMode;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository.VersionedSettings;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link DynamoDbLotteryRepository} のテスト。DynamoDB Local を使う。 */
class DynamoDbLotteryRepositoryTest {

    private final LotteryRepository repository = LocalDynamoDb.newStorage().lotteries();

    @Test
    void 抽選記録を新しい順に読み戻せる() {
        LotteryRecord older = new LotteryRecord("l1", LotteryMode.BULK, Instant.parse("2026-10-01T10:00:00Z"), "配信者",
                42L, List.of(new LotteryRecord.Entry("a1", 1.0, true), new LotteryRecord.Entry("a2", 2.5, false)));
        LotteryRecord newer = new LotteryRecord("l2", LotteryMode.LIVE, Instant.parse("2026-10-02T10:00:00Z"), "運営",
                -7L, List.of(new LotteryRecord.Entry("a3", 1.0, true)));
        repository.save(older);
        repository.save(newer);

        assertThat(repository.findAll()).containsExactly(newer, older);
    }

    @Test
    void 設定はまだなければ初期設定で版1() {
        assertThat(repository.loadSettings()).isEqualTo(new VersionedSettings(LotterySettings.DEFAULT, 1));
    }

    @Test
    void 設定は版が合うときだけ保存できる() {
        LotterySettings changed = new LotterySettings(false, true, 0.5);

        assertThat(repository.saveSettings(changed, 1)).isEqualTo(new VersionedSettings(changed, 2));
        assertThat(repository.loadSettings()).isEqualTo(new VersionedSettings(changed, 2));

        // 古い版（1）のまま保存しようとすると断られ、設定は変わらない
        assertThat(repository.saveSettings(LotterySettings.DEFAULT, 1)).isNull();
        assertThat(repository.loadSettings().settings()).isEqualTo(changed);
    }
}
