package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getS;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.s;

import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * 配信の操作の許可を DynamoDB に保存する {@link StreamOperatorRepository} の実装。
 *
 * <p>PK = {@code STREAM}、SK = {@code OPERATOR#ユーザーID}（docs/design.md 2章）。許可した人の分だけアイテムがあり、
 * 取り消したらアイテムを消す。許可した日時と、許可した人も残す。
 */
public class DynamoDbStreamOperatorRepository implements StreamOperatorRepository {

    private static final String PK_VALUE = "STREAM";
    private static final String SK_PREFIX = "OPERATOR#";

    private final DynamoDbClient client;
    private final String tableName;
    private final Clock clock;

    public DynamoDbStreamOperatorRepository(DynamoDbClient client, String tableName, Clock clock) {
        this.client = client;
        this.tableName = tableName;
        this.clock = clock;
    }

    @Override
    public Set<String> findAll() {
        Set<String> result = new HashSet<>();
        // PK が STREAM で、SK が OPERATOR# で始まるものだけを読む（同じ PK に配信の操作履歴なども置くため）
        client.queryPaginator(q -> q
                        .tableName(tableName)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                        .expressionAttributeValues(Map.of(":pk", s(PK_VALUE), ":prefix", s(SK_PREFIX)))
                        // 強い整合性で読む（許可・取り消しの直後に古い状態を返さないように）
                        .consistentRead(true))
                .items()
                .forEach(item -> result.add(getS(item, "userId")));
        return result;
    }

    @Override
    public boolean isOperator(String userId) {
        return client.getItem(g -> g.tableName(tableName).key(key(userId)).consistentRead(true)).hasItem();
    }

    @Override
    public void grant(String userId, String grantedBy) {
        Map<String, AttributeValue> item = new HashMap<>(key(userId));
        item.put("type", s("STREAM_OPERATOR"));
        item.put("userId", s(userId));
        item.put("grantedAt", s(clock.instant().toString()));
        item.put("grantedBy", s(grantedBy));
        client.putItem(p -> p.tableName(tableName).item(item));
    }

    @Override
    public void revoke(String userId) {
        client.deleteItem(d -> d.tableName(tableName).key(key(userId)));
    }

    private static Map<String, AttributeValue> key(String userId) {
        return Map.of(MainTable.PK, s(PK_VALUE), MainTable.SK, s(SK_PREFIX + userId));
    }
}
