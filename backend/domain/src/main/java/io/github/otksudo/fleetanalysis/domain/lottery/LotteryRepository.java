package io.github.otksudo.fleetanalysis.domain.lottery;

import java.util.List;

/** 抽選記録と抽選設定の保存先の約束。実際の保存先は infra が決める。 */
public interface LotteryRepository {

    void save(LotteryRecord record);

    /** すべての抽選記録（新しい順）。 */
    List<LotteryRecord> findAll();

    /** 抽選設定と、その版（楽観ロック用）。 */
    VersionedSettings loadSettings();

    /**
     * 抽選設定を保存する。
     *
     * @param expectedVersion 読み込んだときの版。今の版と違えば、誰かが先に変更しているので保存しない
     * @return 保存できたら新しい版の設定。版が違えば null
     */
    VersionedSettings saveSettings(LotterySettings settings, int expectedVersion);

    /**
     * 版つきの抽選設定。
     *
     * <p>「楽観ロック」: 2人が同時に設定を変えたとき、後から保存した人が先の人の変更を気づかず上書きしないよう、
     * 読み込んだときの版を一緒に送ってもらい、版が変わっていたら保存を断る仕組み。
     */
    record VersionedSettings(LotterySettings settings, int version) {
    }
}
