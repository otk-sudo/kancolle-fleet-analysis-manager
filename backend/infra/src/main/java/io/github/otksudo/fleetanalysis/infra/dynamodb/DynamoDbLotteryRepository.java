package io.github.otksudo.fleetanalysis.infra.dynamodb;

import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.bool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getBool;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getDouble;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getLong;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.getS;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.n;
import static io.github.otksudo.fleetanalysis.infra.dynamodb.AttributeValues.s;

import io.github.otksudo.fleetanalysis.domain.lottery.LotteryMode;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * 抽選記録と抽選設定を DynamoDB に保存する {@link LotteryRepository} の実装。
 *
 * <ul>
 *   <li>抽選記録: PK = {@code LOTTERY#抽選ID}、SK = {@code META}。対象者ごとの結果（entries）も同じアイテムに入れる。
 *       1件のアイテムは最大400KBまでで、対象者1人分は100バイトほどなので、数千人でも収まる
 *   <li>抽選設定: PK = {@code SETTINGS}、SK = {@code LOTTERY}。版（version）を持たせて、同時の変更を見分ける
 * </ul>
 */
public class DynamoDbLotteryRepository implements LotteryRepository {

    private static final Map<String, AttributeValue> SETTINGS_KEY =
            Map.of(MainTable.PK, s("SETTINGS"), MainTable.SK, s("LOTTERY"));

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbLotteryRepository(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public void save(LotteryRecord record) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(MainTable.PK, s("LOTTERY#" + record.id()));
        item.put(MainTable.SK, s("META"));
        item.put("type", s("LOTTERY"));
        // GSI2 の「LOTTERIES」に実行日時の順で並べる（一覧を新しい順に出すため）
        item.put(MainTable.GSI2_PK, s("LOTTERIES"));
        item.put(MainTable.GSI2_SK, s(AttributeValues.sortableTime(record.executedAt()) + "#" + record.id()));
        item.put("id", s(record.id()));
        item.put("mode", s(record.mode().code()));
        item.put("executedAt", s(record.executedAt().toString()));
        item.put("seed", n(record.seed()));
        List<AttributeValue> entries = new ArrayList<>();
        for (LotteryRecord.Entry entry : record.entries()) {
            entries.add(AttributeValue.fromM(Map.of(
                    "applicationId", s(entry.applicationId()),
                    "weight", n(entry.weight()),
                    "won", bool(entry.won()))));
        }
        item.put("entries", AttributeValue.fromL(entries));
        client.putItem(p -> p.tableName(tableName).item(item));
    }

    @Override
    public List<LotteryRecord> findAll() {
        List<LotteryRecord> result = new ArrayList<>();
        client.queryPaginator(q -> q
                        .tableName(tableName)
                        .indexName(MainTable.GSI2)
                        .keyConditionExpression("GSI2PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", s("LOTTERIES")))
                        // scanIndexForward(false): ソートキーの大きい順（＝新しい順）に読む
                        .scanIndexForward(false))
                .items()
                .forEach(item -> result.add(fromItem(item)));
        return result;
    }

    @Override
    public VersionedSettings loadSettings() {
        Map<String, AttributeValue> item = client.getItem(g -> g
                .tableName(tableName)
                .key(SETTINGS_KEY)
                .consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            // まだ一度も保存していなければ初期設定。版は1から始める
            return new VersionedSettings(LotterySettings.DEFAULT, 1);
        }
        LotterySettings settings = new LotterySettings(
                getBool(item, "enabled"), getBool(item, "lossBonusEnabled"), getDouble(item, "lossBonusStrength"));
        return new VersionedSettings(settings, (int) getLong(item, "version"));
    }

    /**
     * 版が読み込んだときと同じときだけ保存する（条件付き書き込み）。
     * 条件は DynamoDB が書き込みと同時に確かめるので、2人がほぼ同時に保存しても片方だけが成功する。
     */
    @Override
    public VersionedSettings saveSettings(LotterySettings settings, int expectedVersion) {
        int nextVersion = expectedVersion + 1;
        Map<String, AttributeValue> item = new HashMap<>(SETTINGS_KEY);
        item.put("enabled", bool(settings.enabled()));
        item.put("lossBonusEnabled", bool(settings.lossBonusEnabled()));
        item.put("lossBonusStrength", n(settings.lossBonusStrength()));
        item.put("version", n(nextVersion));
        // 版1は「まだ一度も保存していない」状態（loadSettings を参照）なので、アイテムがなくてもよい
        String condition = expectedVersion == 1
                ? "attribute_not_exists(PK) OR version = :expected"
                : "version = :expected";
        try {
            client.putItem(p -> p
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression(condition)
                    .expressionAttributeValues(Map.of(":expected", n(expectedVersion))));
        } catch (ConditionalCheckFailedException e) {
            return null; // 誰かが先に変更していた
        }
        return new VersionedSettings(settings, nextVersion);
    }

    private static LotteryRecord fromItem(Map<String, AttributeValue> item) {
        List<LotteryRecord.Entry> entries = new ArrayList<>();
        for (AttributeValue value : item.get("entries").l()) {
            Map<String, AttributeValue> m = value.m();
            entries.add(new LotteryRecord.Entry(getS(m, "applicationId"), getDouble(m, "weight"), getBool(m, "won")));
        }
        return new LotteryRecord(
                getS(item, "id"),
                LotteryMode.fromCode(getS(item, "mode")),
                Instant.parse(getS(item, "executedAt")),
                getLong(item, "seed"),
                entries);
    }
}
