package io.github.otksudo.fleetanalysis.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 関係者への配信の操作の許可（仕様 2章）のテスト。 */
class StreamOperatorServiceTest {

    /** テスト用の、メモリに置くだけの保存先 */
    private static final class Operators implements StreamOperatorRepository {
        private final Set<String> ids = new HashSet<>();

        @Override
        public Set<String> findAll() {
            return Set.copyOf(ids);
        }

        @Override
        public boolean isOperator(String userId) {
            return ids.contains(userId);
        }

        @Override
        public void grant(String userId, String grantedBy) {
            ids.add(userId);
        }

        @Override
        public void revoke(String userId) {
            ids.remove(userId);
        }
    }

    /** テスト用の名簿 */
    private static final UserDirectory DIRECTORY = new UserDirectory() {
        private final List<Entry> entries = List.of(
                new Entry("streamer", "配信者", Role.STREAMER),
                new Entry("staff-a", "関係者A", Role.STAFF),
                new Entry("staff-b", "関係者B", Role.STAFF));

        @Override
        public List<Entry> findAll() {
            return entries;
        }

        @Override
        public Optional<Entry> find(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }
    };

    private final StreamOperatorService service = new StreamOperatorService(new Operators(), DIRECTORY);

    @Test
    void はじめは誰も許可されていない() {
        assertThat(service.list()).extracting(StreamOperatorService.Operator::allowed).containsOnly(false);
    }

    @Test
    void 関係者だけが一覧に出て_許可と取り消しができる() {
        assertThat(service.list()).extracting(StreamOperatorService.Operator::userId)
                .containsExactly("staff-a", "staff-b");

        service.grant("staff-a", "配信者");
        assertThat(service.isOperator("staff-a")).isTrue();
        assertThat(service.isOperator("staff-b")).isFalse();

        service.revoke("staff-a");
        assertThat(service.isOperator("staff-a")).isFalse();
    }

    @Test
    void 関係者でない人や名簿にいない人は許可できない() {
        assertThatThrownBy(() -> service.grant("streamer", "運営")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> service.grant("nobody", "運営")).isInstanceOf(NotFoundException.class);
    }
}
