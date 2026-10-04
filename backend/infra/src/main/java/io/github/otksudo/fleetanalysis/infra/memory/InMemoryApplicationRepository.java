package io.github.otksudo.fleetanalysis.infra.memory;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.HistoryEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 応募をメモリ上に保存する {@link ApplicationRepository} の実装（試作・テスト用）。
 *
 * <p>SQLite の実装と同じ動きになるよう、次のことに気をつけている。
 * <ul>
 *   <li>保存するときも読み込むときも、応募のコピーを渡す。同じオブジェクトをそのまま渡すと、
 *       保存する前の変更がほかの処理から見えてしまい、SQLite とは違う動きになるため
 *   <li>版（version）が読み込んだときと違えば保存を断る（同時変更の検知）
 *   <li>全部保存するか、1件も保存しないか（saveAll）
 * </ul>
 *
 * <p>Webアプリは同時に複数のリクエストを処理するため、読み書きするメソッドには {@code synchronized} をつけ、
 * 同時に1つの処理だけが中身にさわるようにしている。
 */
public class InMemoryApplicationRepository implements ApplicationRepository {

    private final Map<String, Application> byId = new LinkedHashMap<>();
    private final Map<String, List<HistoryEntry>> historyById = new LinkedHashMap<>();

    @Override
    public synchronized void saveAll(List<Application> applications) {
        // 先に全部を確かめ、1件でも保存できなければ何も変えずに断る
        Set<String> submissionIdsInThisCall = new HashSet<>();
        for (Application application : applications) {
            Application stored = byId.get(application.id());
            long storedVersion = stored == null ? 0 : stored.version();
            if (storedVersion != application.version()) {
                throw new ConflictException(
                        "ほかの人が先にこの応募を変更しました。画面を読み込み直してから、もう一度変更してください");
            }
            Optional<Application> sameSubmission = findBySubmissionId(application.submissionId());
            if ((sameSubmission.isPresent() && !sameSubmission.get().id().equals(application.id()))
                    || (stored == null && !submissionIdsInThisCall.add(application.submissionId()))) {
                throw new ConflictException("同じフォームの回答がすでに別の応募として登録されています");
            }
        }
        for (Application application : applications) {
            historyById.computeIfAbsent(application.id(), id -> new ArrayList<>()).addAll(application.pendingHistory());
            application.markSaved();
            byId.put(application.id(), application.copy());
        }
    }

    @Override
    public synchronized Optional<Application> findById(String id) {
        return Optional.ofNullable(byId.get(id)).map(Application::copy);
    }

    @Override
    public synchronized Optional<Application> findBySubmissionId(String submissionId) {
        for (Application application : byId.values()) {
            if (application.submissionId().equals(submissionId)) {
                return Optional.of(application.copy());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<Application> findAll() {
        List<Application> result = new ArrayList<>();
        for (Application application : byId.values()) {
            result.add(application.copy());
        }
        return result;
    }

    @Override
    public synchronized List<Application> findByXId(XId xId) {
        List<Application> result = new ArrayList<>();
        for (Application application : byId.values()) {
            if (application.xId().equals(xId)) {
                result.add(application.copy());
            }
        }
        return result;
    }

    @Override
    public synchronized List<HistoryEntry> findHistory(String applicationId) {
        return List.copyOf(historyById.getOrDefault(applicationId, List.of()));
    }

    @Override
    public synchronized void deleteByXId(XId xId) {
        for (Application application : findByXId(xId)) {
            byId.remove(application.id());
            historyById.remove(application.id());
        }
    }
}
