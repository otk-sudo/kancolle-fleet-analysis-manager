package io.github.otksudo.fleetanalysis.infra.memory;

import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorRepository;
import java.util.HashSet;
import java.util.Set;

/** 配信の操作の許可をメモリに置く {@link StreamOperatorRepository} の実装（試作・テスト用。止めると消える）。 */
public class InMemoryStreamOperatorRepository implements StreamOperatorRepository {

    private final Set<String> operators = new HashSet<>();

    // synchronized: 同時に2つのリクエストが来ても、Set が壊れないように1つずつ処理する
    @Override
    public synchronized Set<String> findAll() {
        return Set.copyOf(operators);
    }

    @Override
    public synchronized boolean isOperator(String userId) {
        return operators.contains(userId);
    }

    @Override
    public synchronized void grant(String userId, String grantedBy) {
        operators.add(userId);
    }

    @Override
    public synchronized void revoke(String userId) {
        operators.remove(userId);
    }
}
