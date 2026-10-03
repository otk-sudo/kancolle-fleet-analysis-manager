package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.bool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getBool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getLong;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getS;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.n;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.putIfNotNull;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.s;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * 応募を DynamoDB に保存する {@link ApplicationRepository} の実装。
 *
 * <p>応募1件につき、次の2つのアイテムを保存する。
 * <ul>
 *   <li>応募本体: PK = {@code APP#応募ID}、SK = {@code META}
 *   <li>回答IDの控え: PK = {@code SUB#フォームの回答ID}、SK = {@code META}。中身は応募IDだけ。
 *       「同じ回答IDで2件登録しない」ことを DynamoDB に守らせるために使う（下の save を参照）
 * </ul>
 *
 * <p>注意: GSI（索引）からの読み込みは「結果整合性」で、保存した直後（1秒未満）はまだ索引に反映されていないことがある
 * （公式: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html ）。
 * 索引に載っている中身をそのまま使うと、直前に変えたステータスが古いまま見え、それを保存し直して変更を消してしまう。
 * そこで findAll と findByXId は、索引では「どの応募があるか（キー）」だけを調べ、中身は強い整合性で読み直す。
 * それでも、保存した直後の「新しい応募」が一覧に出てこないことはまれにある（索引にまだ載っていないため）。
 * TODO(段階2): 応募に版を持たせ、古い内容での上書きを条件付き書き込みで断る（仕様 5.1 の同時変更の検知）
 */
public class DynamoDbApplicationRepository implements ApplicationRepository {

    private static final String META = "META";

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbApplicationRepository(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    /**
     * 応募を保存する。
     *
     * <p>「トランザクション」（全部成功するか、全部失敗するか）で、応募本体と回答IDの控えを一緒に書く。
     * 回答IDの控えには「まだないか、あっても同じ応募IDのときだけ書く」という条件をつける。
     * 別の応募IDで控えがすでにあれば（同じ回答が同時に2回届いた場合）、全部を取りやめて ConflictException にする。
     *
     * <p>控えは、上書き保存（ステータスの変更など）のたびにも書き直している。新規か上書きかを見分ける手間を省くためで、
     * 書き込みの量は2倍になるが、応募数（数千件）の規模なら料金への影響は小さい。
     * なお、同じアイテムへの別のトランザクションと同時になると DynamoDB が取りやめる（TransactionConflict）ことがあり、
     * そのときは例外がそのまま上に伝わる（画面には「失敗したのでやり直してください」が出る）。
     */
    @Override
    public void save(Application application) {
        TransactWriteItem putApplication = TransactWriteItem.builder()
                .put(p -> p.tableName(tableName).item(toItem(application)))
                .build();
        TransactWriteItem putSubmission = TransactWriteItem.builder()
                .put(p -> p.tableName(tableName)
                        .item(Map.of(
                                MainTable.PK, s(submissionKey(application.submissionId())),
                                MainTable.SK, s(META),
                                "applicationId", s(application.id())))
                        // attribute_not_exists(PK): このキーのアイテムがまだない
                        .conditionExpression("attribute_not_exists(PK) OR applicationId = :id")
                        .expressionAttributeValues(Map.of(":id", s(application.id()))))
                .build();
        try {
            client.transactWriteItems(t -> t.transactItems(putApplication, putSubmission));
        } catch (TransactionCanceledException e) {
            if (isConditionFailed(e)) {
                throw new ConflictException("同じフォームの回答がすでに別の応募として登録されています");
            }
            throw e;
        }
    }

    @Override
    public Optional<Application> findById(String id) {
        // consistentRead(true): 「強い整合性」で読む。直前に保存した内容も必ず読める
        Map<String, AttributeValue> item = client.getItem(g -> g
                .tableName(tableName)
                .key(key(applicationKey(id)))
                .consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(item));
    }

    @Override
    public Optional<Application> findBySubmissionId(String submissionId) {
        Map<String, AttributeValue> item = client.getItem(g -> g
                .tableName(tableName)
                .key(key(submissionKey(submissionId)))
                .consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return findById(getS(item, "applicationId"));
    }

    @Override
    public List<Application> findAll() {
        // GSI2 の「APPS」には全部の応募が受付順に並んでいる
        return query(QueryRequest.builder()
                .tableName(tableName)
                .indexName(MainTable.GSI2)
                .keyConditionExpression("GSI2PK = :pk")
                .expressionAttributeValues(Map.of(":pk", s("APPS")))
                .build());
    }

    @Override
    public List<Application> findByXId(XId xId) {
        return query(QueryRequest.builder()
                .tableName(tableName)
                .indexName(MainTable.GSI3)
                .keyConditionExpression("GSI3PK = :pk")
                .expressionAttributeValues(Map.of(":pk", s(xIdKey(xId))))
                .build());
    }

    /**
     * 同じXのIDの応募を、回答IDの控えも含めて消す（仕様 8.1 削除依頼への対応）。
     *
     * <p>応募本体と回答IDの控えは、トランザクションで一緒に消す。片方だけ残ると、
     * 控えだけが残った回答をあとで再送したときに、ずっと「登録済み」扱いで断られてしまうため。
     *
     * <p>TODO(段階6): ステータス履歴（STATUS#）と、抽選記録の中の応募IDの置き換え（「削除済み」にする）も行う。
     */
    @Override
    public void deleteByXId(XId xId) {
        for (Application application : findByXId(xId)) {
            client.transactWriteItems(t -> t.transactItems(
                    TransactWriteItem.builder()
                            .delete(d -> d.tableName(tableName).key(key(applicationKey(application.id()))))
                            .build(),
                    TransactWriteItem.builder()
                            .delete(d -> d.tableName(tableName).key(key(submissionKey(application.submissionId()))))
                            .build()));
        }
    }

    /**
     * 索引で応募のキーを探し、中身は本体から強い整合性で読み直す（クラスの説明の「注意」を参照）。
     *
     * <p>DynamoDB は1回に最大1MBまでしか返さないので、続きがあれば繰り返し読む（queryPaginator が自動でやる）。
     * 読み直しは BatchGetItem（まとめて読む）で、1回に100件までなので100件ずつに分ける。
     */
    private List<Application> query(QueryRequest request) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        // projectionExpression: 索引からはキー（PK・SK）だけを受け取る
        QueryRequest keysOnly = request.toBuilder().projectionExpression("PK, SK").build();
        client.queryPaginator(keysOnly).items().forEach(item ->
                keys.add(Map.of(MainTable.PK, item.get(MainTable.PK), MainTable.SK, item.get(MainTable.SK))));

        Map<String, Map<String, AttributeValue>> byPk = new HashMap<>();
        for (int from = 0; from < keys.size(); from += BATCH_GET_LIMIT) {
            List<Map<String, AttributeValue>> chunk = keys.subList(from, Math.min(from + BATCH_GET_LIMIT, keys.size()));
            for (Map<String, AttributeValue> item : batchGetConsistent(chunk)) {
                byPk.put(item.get(MainTable.PK).s(), item);
            }
        }
        // 索引で見つけた順（受付順）に並べる。読み直す間に消された応募は飛ばす
        List<Application> result = new ArrayList<>();
        for (Map<String, AttributeValue> key : keys) {
            Map<String, AttributeValue> item = byPk.get(key.get(MainTable.PK).s());
            if (item != null) {
                result.add(fromItem(item));
            }
        }
        return result;
    }

    private static final int BATCH_GET_LIMIT = 100;

    /**
     * キーのアイテムをまとめて、強い整合性で読む。
     * DynamoDB が混んでいると一部を「あとで」（UnprocessedKeys）として返すので、全部読めるまで繰り返す。
     */
    private List<Map<String, AttributeValue>> batchGetConsistent(List<Map<String, AttributeValue>> keys) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, KeysAndAttributes> request = Map.of(tableName,
                KeysAndAttributes.builder().keys(keys).consistentRead(true).build());
        while (!request.isEmpty()) {
            Map<String, KeysAndAttributes> current = request;
            BatchGetItemResponse response = client.batchGetItem(b -> b.requestItems(current));
            items.addAll(response.responses().getOrDefault(tableName, List.of()));
            request = response.unprocessedKeys();
        }
        return items;
    }

    // ---- Java の Application と DynamoDB のアイテムの変換 ----

    private static Map<String, AttributeValue> toItem(Application application) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(MainTable.PK, s(applicationKey(application.id())));
        item.put(MainTable.SK, s(META));
        item.put("type", s("APPLICATION"));
        // 索引（GSI）のためのキー。値を入れたアイテムだけが索引に載る
        item.put(MainTable.GSI1_PK, s("STATUS#" + application.status().code()));
        item.put(MainTable.GSI1_SK, s(positionKey(application.position())));
        String receivedOrder = AttributeValues.sortableTime(application.receivedAt()) + "#" + application.id();
        item.put(MainTable.GSI2_PK, s("APPS"));
        item.put(MainTable.GSI2_SK, s(receivedOrder));
        item.put(MainTable.GSI3_PK, s(xIdKey(application.xId())));
        item.put(MainTable.GSI3_SK, s(receivedOrder));

        item.put("id", s(application.id()));
        item.put("submissionId", s(application.submissionId()));
        item.put("xId", s(application.xId().value()));
        item.put("admiralName", s(application.admiralName()));
        item.put("anonymous", bool(application.anonymous()));
        item.put("simulatorUrl", s(application.simulatorUrl()));
        item.put("formVersion", s(application.formVersion()));
        item.put("answers", AttributeValue.fromM(AttributeValues.fromObjectMap(application.answers())));
        item.put("receivedAt", s(application.receivedAt().toString()));
        item.put("flags", AttributeValue.fromL(application.flags().stream().map(DynamoDbApplicationRepository::toFlagValue).toList()));
        item.put("status", s(application.status().code()));
        putIfNotNull(item, "streamDate", application.streamDate() == null ? null : application.streamDate().toString());
        putIfNotNull(item, "memo", application.memo());
        item.put("position", n(application.position()));
        item.put("wonLottery", bool(application.wonLottery()));
        item.put("updatedAt", s(application.updatedAt().toString()));
        item.put("statusChangedAt", s(application.statusChangedAt().toString()));
        return item;
    }

    private static Application fromItem(Map<String, AttributeValue> item) {
        List<Flag> flags = new ArrayList<>();
        for (AttributeValue flag : item.get("flags").l()) {
            flags.add(fromFlagValue(flag));
        }
        String streamDate = getS(item, "streamDate");
        return Application.restore(
                getS(item, "id"),
                getS(item, "submissionId"),
                XId.parse(getS(item, "xId")),
                getS(item, "admiralName"),
                getBool(item, "anonymous"),
                getS(item, "simulatorUrl"),
                getS(item, "formVersion"),
                AttributeValues.toObjectMap(item.get("answers").m()),
                Instant.parse(getS(item, "receivedAt")),
                flags,
                new Application.State(
                        ApplicationStatus.fromCode(getS(item, "status")),
                        streamDate == null ? null : LocalDate.parse(streamDate),
                        getS(item, "memo"),
                        getLong(item, "position"),
                        getBool(item, "wonLottery"),
                        Instant.parse(getS(item, "updatedAt")),
                        Instant.parse(getS(item, "statusChangedAt"))));
    }

    private static AttributeValue toFlagValue(Flag flag) {
        Map<String, AttributeValue> value = new HashMap<>();
        value.put("type", s(flag.type().code()));
        value.put("reason", s(flag.reason()));
        putIfNotNull(value, "relatedApplicationId", flag.relatedApplicationId());
        return AttributeValue.fromM(value);
    }

    private static Flag fromFlagValue(AttributeValue value) {
        Map<String, AttributeValue> m = value.m();
        return new Flag(FlagType.fromCode(getS(m, "type")), getS(m, "reason"), getS(m, "relatedApplicationId"));
    }

    // ---- キーの作り方 ----

    private static String applicationKey(String id) {
        return "APP#" + id;
    }

    private static String submissionKey(String submissionId) {
        return "SUB#" + submissionId;
    }

    private static String xIdKey(XId xId) {
        return "XID#" + xId.value();
    }

    /**
     * 並び順キーを、文字列として並べても数の順になるよう、先頭を0で埋めた20桁にする。
     * 例: 5 → "00000000000000000005"。そのままだと文字列の順では "10" が "9" より前になってしまうため。
     */
    static String positionKey(long position) {
        return String.format("%020d", position);
    }

    private static Map<String, AttributeValue> key(String pk) {
        return Map.of(MainTable.PK, s(pk), MainTable.SK, s(META));
    }

    /** トランザクションが「条件に合わない」ことで取りやめになったか。 */
    private static boolean isConditionFailed(TransactionCanceledException e) {
        for (CancellationReason reason : e.cancellationReasons()) {
            if ("ConditionalCheckFailed".equals(reason.code())) {
                return true;
            }
        }
        return false;
    }
}
