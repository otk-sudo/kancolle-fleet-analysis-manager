package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

/** 配信の操作の許可を DynamoDB（DynamoDB Local）に保存できるかのテスト。 */
class DynamoDbStreamOperatorRepositoryTest {

    private final DynamoDbStreamOperatorRepository repository =
            new DynamoDbStreamOperatorRepository(LocalDynamoDb.client(), LocalDynamoDb.newTable(), Clock.systemUTC());

    @Test
    void 許可と取り消しを保存できる() {
        assertThat(repository.findAll()).isEmpty();

        repository.grant("staff-a", "配信者");
        repository.grant("staff-b", "配信者");
        repository.grant("staff-a", "配信者"); // 2回許可しても1件のまま
        assertThat(repository.findAll()).containsExactlyInAnyOrder("staff-a", "staff-b");
        assertThat(repository.isOperator("staff-a")).isTrue();

        repository.revoke("staff-a");
        repository.revoke("nobody"); // 許可していない人を取り消しても何も起きない
        assertThat(repository.findAll()).containsExactly("staff-b");
        assertThat(repository.isOperator("staff-a")).isFalse();
    }
}
