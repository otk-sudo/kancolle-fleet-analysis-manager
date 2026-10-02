package io.github.otksudo.fleetanalysis.infra.memory;

import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 抽選記録と抽選設定をメモリ上に保存する {@link LotteryRepository} の実装。 */
public class InMemoryLotteryRepository implements LotteryRepository {

    private final List<LotteryRecord> records = new ArrayList<>();
    private VersionedSettings settings = new VersionedSettings(LotterySettings.DEFAULT, 1);

    // synchronized: 同時に呼ばれても1つずつ順番に実行されるようにする（リストや版の読み書きが混ざらないように）
    @Override
    public synchronized void save(LotteryRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<LotteryRecord> findAll() {
        List<LotteryRecord> result = new ArrayList<>(records);
        result.sort(Comparator.comparing(LotteryRecord::executedAt).reversed());
        return result;
    }

    @Override
    public synchronized VersionedSettings loadSettings() {
        return settings;
    }

    @Override
    public synchronized VersionedSettings saveSettings(LotterySettings newSettings, int expectedVersion) {
        if (settings.version() != expectedVersion) {
            return null;
        }
        settings = new VersionedSettings(newSettings, expectedVersion + 1);
        return settings;
    }
}
