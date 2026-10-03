package io.github.otksudo.fleetanalysis.infra.dynamodb;

import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * テーブルの形（キーとインデックス）の決まり（docs/design.md 2章）。
 *
 * <p>DynamoDB の用語:
 * <ul>
 *   <li>パーティションキー（PK）とソートキー（SK）: 1件のデータ（アイテム）を見分けるための2つの値。
 *       PK が同じアイテムは、SK の順に並んで一緒に保存される
 *   <li>GSI（グローバルセカンダリインデックス）: PK・SK とは別の値で検索するための「索引」。
 *       例えば GSI3 は「XのID」で引けるようにしたもの。本の巻末の索引のようなもの
 * </ul>
 *
 * <p>本番（AWS）のテーブルは段階4で CDK が作る。ここに書いた形と、CDK の定義は同じにしておく必要がある。
 */
public final class MainTable {

    public static final String PK = "PK";
    public static final String SK = "SK";

    /** ステータス別の並び（次に分析する順）。GSI1PK = STATUS#ステータス、GSI1SK = 並び順キー */
    public static final String GSI1 = "GSI1";
    public static final String GSI1_PK = "GSI1PK";
    public static final String GSI1_SK = "GSI1SK";

    /** 種類ごとの一覧（受付順・実行順）。GSI2PK = APPS や LOTTERIES、GSI2SK = 日時#ID */
    public static final String GSI2 = "GSI2";
    public static final String GSI2_PK = "GSI2PK";
    public static final String GSI2_SK = "GSI2SK";

    /** 同じ応募者の応募。GSI3PK = XID#XのID、GSI3SK = 受付日時#ID */
    public static final String GSI3 = "GSI3";
    public static final String GSI3_PK = "GSI3PK";
    public static final String GSI3_SK = "GSI3SK";

    private MainTable() {
    }

    /**
     * テーブルがなければ作る（手元の DynamoDB Local とテスト用）。
     *
     * <p>本番では使わない（本番のテーブルは CDK で作り、アプリには作る権限を持たせない）。
     * 料金は「使った分だけ払う」オンデマンド（PAY_PER_REQUEST）にしている（仕様 9章）。
     */
    public static void createIfNotExists(DynamoDbClient client, String tableName) {
        try {
            client.describeTable(b -> b.tableName(tableName));
            return; // もうある
        } catch (ResourceNotFoundException e) {
            // ないので、下で作る
        }
        client.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        stringAttribute(PK), stringAttribute(SK),
                        stringAttribute(GSI1_PK), stringAttribute(GSI1_SK),
                        stringAttribute(GSI2_PK), stringAttribute(GSI2_SK),
                        stringAttribute(GSI3_PK), stringAttribute(GSI3_SK))
                .keySchema(hashKey(PK), rangeKey(SK))
                .globalSecondaryIndexes(
                        index(GSI1, GSI1_PK, GSI1_SK),
                        index(GSI2, GSI2_PK, GSI2_SK),
                        index(GSI3, GSI3_PK, GSI3_SK))
                .build());
        // 作り終わる（使える状態になる）まで待つ
        client.waiter().waitUntilTableExists(b -> b.tableName(tableName));
    }

    private static AttributeDefinition stringAttribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement hashKey(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.HASH).build();
    }

    private static KeySchemaElement rangeKey(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.RANGE).build();
    }

    private static GlobalSecondaryIndex index(String name, String pk, String sk) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(List.of(hashKey(pk), rangeKey(sk)))
                // ALL: 索引にもアイテムの全項目を入れる。索引から引いたあと、元のアイテムを読み直さずに済む
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }
}
