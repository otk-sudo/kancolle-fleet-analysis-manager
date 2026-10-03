package io.github.otksudo.fleetanalysis.infra.memory;

import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 応募をメモリ上に保存する {@link ApplicationRepository} の実装。
 *
 * <p>{@link ConcurrentHashMap} は、複数のリクエストが同時に読み書きしても壊れない Map。
 * Webアプリは同時に複数のリクエストを処理するため、普通の HashMap ではなくこちらを使う。
 */
public class InMemoryApplicationRepository implements ApplicationRepository {

    private final Map<String, Application> byId = new ConcurrentHashMap<>();

    @Override
    public void save(Application application) {
        byId.put(application.id(), application);
    }

    @Override
    public Optional<Application> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Application> findBySubmissionId(String submissionId) {
        for (Application application : byId.values()) {
            if (application.submissionId().equals(submissionId)) {
                return Optional.of(application);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Application> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public List<Application> findByXId(XId xId) {
        List<Application> result = new ArrayList<>();
        for (Application application : byId.values()) {
            if (application.xId().equals(xId)) {
                result.add(application);
            }
        }
        return result;
    }

    @Override
    public void deleteByXId(XId xId) {
        byId.values().removeIf(application -> application.xId().equals(xId));
    }
}
