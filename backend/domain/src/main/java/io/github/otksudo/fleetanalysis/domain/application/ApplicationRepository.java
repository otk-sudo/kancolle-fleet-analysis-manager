package io.github.otksudo.fleetanalysis.domain.application;

import io.github.otksudo.fleetanalysis.domain.XId;
import java.util.List;
import java.util.Optional;

/**
 * 応募の保存先の「約束」（インターフェース）。
 *
 * <p>domain は「保存できること」だけを決め、実際にどこへ保存するか（メモリ、DynamoDB）は infra が決める。
 * こうしておくと、試作ではメモリ、本番では DynamoDB、と差し替えても業務ロジックは変えずに済む。
 */
public interface ApplicationRepository {

    /** 新規保存または上書き保存する。 */
    void save(Application application);

    Optional<Application> findById(String id);

    /** フォームの回答ID（同じ回答の再送を見分けるため）で探す。 */
    Optional<Application> findBySubmissionId(String submissionId);

    /** すべての応募（順番は決まっていない）。 */
    List<Application> findAll();

    /** 同じXのIDの応募（順番は決まっていない）。 */
    List<Application> findByXId(XId xId);

    /** 同じXのIDの応募をすべて消す（削除依頼への対応）。 */
    void deleteByXId(XId xId);
}
