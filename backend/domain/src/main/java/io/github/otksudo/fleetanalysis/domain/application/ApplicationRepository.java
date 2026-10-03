package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.XId;
import java.util.List;
import java.util.Optional;

/**
 * 応募の保存先の「約束」（インターフェース）。
 *
 * <p>domain は「保存できること」だけを決め、実際にどこへ保存するか（メモリ、SQLite）は infra が決める。
 * こうしておくと、テストではメモリ、配信者さんのPCでは SQLite、と差し替えても業務ロジックは変えずに済む。
 */
public interface ApplicationRepository {

    /**
     * 1件を新規保存または上書き保存する。{@link #saveAll} に1件だけ渡すのと同じ。
     */
    default void save(Application application) {
        saveAll(List.of(application));
    }

    /**
     * 複数の応募を「全部保存するか、1件も保存しないか」のどちらかで保存する（まとめてのステータス変更などで使う）。
     *
     * <p>それぞれの応募は、読み込んだときと版（{@link Application#version()}）が同じときだけ保存する。
     * 保存できたら版を1つ進め、まだ保存していない変更履歴（{@link Application#pendingHistory()}）も一緒に保存する
     * （{@link Application#markSaved()} を呼ぶ）。
     *
     * @throws io.github.otksudo.fleetanalysis.domain.ConflictException
     *         次のどれかのとき（1件も保存しない）。
     *         <ul>
     *           <li>ほかの人が先に同じ応募を保存していた（版が違う）
     *           <li>同じフォームの回答ID（submissionId）が、別の応募としてすでに保存されている。
     *               同じ回答がほぼ同時に2回届いたときに、二重登録を防ぐための最後の砦
     *         </ul>
     */
    void saveAll(List<Application> applications);

    /** 応募の変更履歴（古い順）。 */
    List<HistoryEntry> findHistory(String applicationId);

    Optional<Application> findById(String id);

    /** フォームの回答ID（同じ回答の再送を見分けるため）で探す。 */
    Optional<Application> findBySubmissionId(String submissionId);

    /** すべての応募（順番は決まっていない）。 */
    List<Application> findAll();

    /** 同じXのIDの応募（順番は決まっていない）。 */
    List<Application> findByXId(XId xId);

    /** 同じXのIDの応募を、変更履歴も含めてすべて消す（削除依頼への対応）。 */
    void deleteByXId(XId xId);
}
