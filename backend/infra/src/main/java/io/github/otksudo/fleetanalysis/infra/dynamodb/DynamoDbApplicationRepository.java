package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.bool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getBool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getLong;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getS;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.n;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.putIfNotNull;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.s;

import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import io.github.otksudo.fleetanalysis.domain.application.Flag;
import io.github.otksudo.fleetanalysis.domain.application.FlagType;
import io.github.otksudo.fleetanalysis.domain.application.HistoryEntry;
import io.github.otksudo.fleetanalysis.domain.application.SkipReason;
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
 * <p>応募1件につき、次のアイテムを保存する。
 * <ul>
 *   <li>応募本体: PK = {@code APP#応募ID}、SK = {@code META}
 *   <li>回答IDの控え: PK = {@code SUB#フォームの回答ID}、SK = {@code META}。中身は応募IDだけ。
 *       「同じ回答IDで2件登録しない」ことを DynamoDB に守らせるために使う（下の saveAll を参照）
 *   <li>変更履歴: PK = {@code APP#応募ID}、SK = {@code HISTORY#日時#履歴ID}。ステータスやXのIDを変えるたびに1件増える。
 *       応募本体と同じ PK にしておくと、「この応募の履歴」を1回の読み込み（Query）でまとめて取れる
 * </ul>
 *
 * <p>注意: GSI（索引）からの読み込みは「結果整合性」で、保存した直後（1秒未満）はまだ索引に反映されていないことがある
 * （公式: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html ）。
 * 索引に載っている中身をそのまま使うと、直前に変えたステータスが古いまま見え、それを保存し直して変更を消してしまう。
 * そこで findAll と findByXId は、索引では「どの応募があるか（キー）」だけを調べ、中身は強い整合性で読み直す。
 * それでも、保存した直後の「新しい応募」が一覧に出てこないことはまれにある（索引にまだ載っていないため）。
 * 古い内容での上書きは、版（version）を使った条件付き書き込みで断る（saveAll を参照）。
 */
public class DynamoDbApplicationRepository implements ApplicationRepository {

    private static final String META = "META";
    private static final String HISTORY_PREFIX = "HISTORY#";

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbApplicationRepository(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    /**
     * DynamoDB の1回のトランザクションに入れられる書き込みの最大数。
     * 公式: https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html
     */
    private static final int TRANSACTION_LIMIT = 100;

    /**
     * 応募をまとめて保存する。全部保存できるか、1件も保存しないか（トランザクション）。
     *
     * <p>応募ごとに、次の書き込みを1つのトランザクションに入れる。
     * <ul>
     *   <li>応募本体。新しい応募（版0）なら「まだないときだけ」、保存済みなら「保存されている版が読み込んだときと同じときだけ」
     *       書く（条件付き書き込み）。書くときは版を1つ増やす。誰かが先に保存していれば版が違うので断られ、
     *       相手の変更を消さずに済む（仕様 5.1 の同時変更の検知）
     *   <li>回答IDの控え（新しい応募のときだけ）。「まだないか、あっても同じ応募IDのときだけ書く」という条件をつける。
     *       別の応募IDで控えがすでにあれば（同じ回答が同時に2回届いた場合）、全部を取りやめる
     *   <li>まだ保存していない変更履歴
     * </ul>
     * 条件に合わなければ ConflictException にする。
     * なお、同じアイテムへの別のトランザクションと同時になると DynamoDB が取りやめる（TransactionConflict）ことがあり、
     * そのときも ConflictException にする（画面には「もう一度お試しください」が出る）。
     */
    @Override
    public void saveAll(List<Application> applications) {
        List<TransactWriteItem> items = new ArrayList<>();
        // 何番目の書き込みが何だったか（断られたときに、理由を分けて伝えるため）
        List<String> kinds = new ArrayList<>();
        for (Application application : applications) {
            items.add(putApplication(application));
            kinds.add("application");
            if (application.version() == 0) {
                items.add(putSubmission(application));
                kinds.add("submission");
            }
            List<HistoryEntry> history = application.pendingHistory();
            for (int i = 0; i < history.size(); i++) {
                Map<String, AttributeValue> historyItem = toHistoryItem(application.id(), history.get(i), i);
                items.add(TransactWriteItem.builder()
                        .put(p -> p.tableName(tableName).item(historyItem))
                        .build());
                kinds.add("history");
            }
        }
        if (items.size() > TRANSACTION_LIMIT) {
            throw new InvalidValueException("一度に保存できる数を超えました。件数を減らしてやり直してください");
        }
        try {
            client.transactWriteItems(t -> t.transactItems(items));
        } catch (TransactionCanceledException e) {
            throw toConflict(e, kinds);
        }
        for (Application application : applications) {
            application.markSaved();
        }
    }

    private TransactWriteItem putApplication(Application application) {
        long version = application.version();
        Map<String, AttributeValue> item = toItem(application, version + 1);
        if (version == 0) {
            // attribute_not_exists(PK): このキーのアイテムがまだない（新しい応募）
            return TransactWriteItem.builder()
                    .put(p -> p.tableName(tableName).item(item).conditionExpression("attribute_not_exists(PK)"))
                    .build();
        }
        // 版が読み込んだときと同じときだけ書く。
        // 段階1で保存した応募には版がない（読み込むと版1になる）ので、版1のときは「版がない」場合も許す
        String condition = version == 1
                ? "attribute_exists(PK) AND (version = :v OR attribute_not_exists(version))"
                : "version = :v";
        return TransactWriteItem.builder()
                .put(p -> p.tableName(tableName).item(item)
                        .conditionExpression(condition)
                        .expressionAttributeValues(Map.of(":v", n(version))))
                .build();
    }

    private TransactWriteItem putSubmission(Application application) {
        return TransactWriteItem.builder()
                .put(p -> p.tableName(tableName)
                        .item(Map.of(
                                MainTable.PK, s(submissionKey(application.submissionId())),
                                MainTable.SK, s(META),
                                "applicationId", s(application.id())))
                        // まだないか、あっても同じ応募IDのときだけ書く
                        .conditionExpression("attribute_not_exists(PK) OR applicationId = :id")
                        .expressionAttributeValues(Map.of(":id", s(application.id()))))
                .build();
    }

    /** トランザクションが取りやめになった理由を見て、画面に出す説明つきの ConflictException にする。 */
    private static RuntimeException toConflict(TransactionCanceledException e, List<String> kinds) {
        List<CancellationReason> reasons = e.cancellationReasons();
        // 理由は書き込みと同じ順に並んで返ってくる
        for (int i = 0; i < reasons.size() && i < kinds.size(); i++) {
            if ("ConditionalCheckFailed".equals(reasons.get(i).code())) {
                if ("submission".equals(kinds.get(i))) {
                    return new ConflictException("同じフォームの回答がすでに別の応募として登録されています");
                }
                return new ConflictException(
                        "ほかの人が先にこの応募を変更しました。画面を読み込み直してから、もう一度変更してください");
            }
        }
        for (CancellationReason reason : reasons) {
            if ("TransactionConflict".equals(reason.code())) {
                return new ConflictException("ほかの操作と重なったため保存できませんでした。もう一度お試しください");
            }
        }
        return e;
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

    @Override
    public List<HistoryEntry> findHistory(String applicationId) {
        List<HistoryEntry> result = new ArrayList<>();
        for (Map<String, AttributeValue> item : queryHistory(applicationId, false)) {
            result.add(fromHistoryItem(item));
        }
        return result;
    }

    /**
     * 応募の変更履歴のアイテムを、SK の順（＝日時の順）に読む。
     *
     * @param keysOnly true ならキー（PK・SK）だけを読む（消すときに使う）
     */
    private List<Map<String, AttributeValue>> queryHistory(String applicationId, boolean keysOnly) {
        // begins_with(SK, :prefix): SK が "HISTORY#" で始まるアイテムだけ
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", s(applicationKey(applicationId)),
                        ":prefix", s(HISTORY_PREFIX)))
                .consistentRead(true);
        if (keysOnly) {
            request.projectionExpression("PK, SK");
        }
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        client.queryPaginator(request.build()).items().forEach(items::add);
        return items;
    }

    /**
     * 同じXのIDの応募を、回答IDの控えと変更履歴も含めて消す（仕様 8.1 削除依頼への対応）。
     *
     * <p>変更履歴には変更前のXのIDが残っていることがあるので、一緒に消す。
     * 履歴は先に消す（応募本体が先に消えると、途中で失敗したときに履歴だけが残り、探せなくなるため）。
     * 応募本体と回答IDの控えは、トランザクションで一緒に消す。片方だけ残ると、
     * 控えだけが残った回答をあとで再送したときに、ずっと「登録済み」扱いで断られてしまうため。
     *
     * <p>TODO(段階6): 抽選記録の中の応募IDの置き換え（「削除済み」にする）も行う。
     */
    @Override
    public void deleteByXId(XId xId) {
        for (Application application : findByXId(xId)) {
            for (Map<String, AttributeValue> item : queryHistory(application.id(), true)) {
                Map<String, AttributeValue> key = Map.of(MainTable.PK, item.get(MainTable.PK), MainTable.SK, item.get(MainTable.SK));
                client.deleteItem(d -> d.tableName(tableName).key(key));
            }
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

    private static Map<String, AttributeValue> toItem(Application application, long newVersion) {
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
        putIfNotNull(item, "skipReason", application.skipReason() == null ? null : application.skipReason().code());
        putIfNotNull(item, "streamDate", application.streamDate() == null ? null : application.streamDate().toString());
        putIfNotNull(item, "memo", application.memo());
        putIfNotNull(item, "analysisMemo", application.analysisMemo());
        putIfNotNull(item, "archiveUrl", application.archiveUrl());
        item.put("position", n(application.position()));
        item.put("wonLottery", bool(application.wonLottery()));
        item.put("updatedAt", s(application.updatedAt().toString()));
        item.put("statusChangedAt", s(application.statusChangedAt().toString()));
        item.put("version", n(newVersion));
        return item;
    }

    private static Application fromItem(Map<String, AttributeValue> item) {
        List<Flag> flags = new ArrayList<>();
        for (AttributeValue flag : item.get("flags").l()) {
            flags.add(fromFlagValue(flag));
        }
        String streamDate = getS(item, "streamDate");
        String skipReason = getS(item, "skipReason");
        // 段階1で保存した応募には版がないので、版1として扱う
        long version = item.containsKey("version") ? getLong(item, "version") : 1;
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
                        skipReason == null ? null : SkipReason.fromCode(skipReason),
                        streamDate == null ? null : LocalDate.parse(streamDate),
                        getS(item, "memo"),
                        getS(item, "analysisMemo"),
                        getS(item, "archiveUrl"),
                        getLong(item, "position"),
                        getBool(item, "wonLottery"),
                        Instant.parse(getS(item, "updatedAt")),
                        Instant.parse(getS(item, "statusChangedAt")),
                        version));
    }

    /**
     * @param sequence 1回の保存の中での順番。1回の変更でXのIDとステータスを両方変えると、同じ時刻の履歴が2件できるので、
     *                 変えた順に並ぶよう、時刻の次に順番を書く
     */
    private static Map<String, AttributeValue> toHistoryItem(String applicationId, HistoryEntry entry, int sequence) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(MainTable.PK, s(applicationKey(applicationId)));
        // 日時を先に書くと、SK の順（文字列の順）が日時の順になる。同じ時刻なら順番（3桁）、最後に履歴IDで見分ける
        item.put(MainTable.SK, s(HISTORY_PREFIX + AttributeValues.sortableTime(entry.at())
                + "#" + String.format("%03d", sequence) + "#" + entry.id()));
        item.put("type", s("HISTORY"));
        item.put("id", s(entry.id()));
        item.put("at", s(entry.at().toString()));
        item.put("actor", s(entry.actor()));
        item.put("kind", s(entry.kind().code()));
        item.put("from", s(entry.from()));
        item.put("to", s(entry.to()));
        putIfNotNull(item, "note", entry.note());
        return item;
    }

    private static HistoryEntry fromHistoryItem(Map<String, AttributeValue> item) {
        return new HistoryEntry(
                getS(item, "id"),
                Instant.parse(getS(item, "at")),
                getS(item, "actor"),
                HistoryEntry.Kind.fromCode(getS(item, "kind")),
                getS(item, "from"),
                getS(item, "to"),
                getS(item, "note"));
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
}
