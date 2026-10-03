package io.github.otksudo.fleetanalysis.infra.dynamodb;

import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.dynamodb.services.local.embedded.DynamoDBEmbedded;
import software.amazon.dynamodb.services.local.shared.access.AmazonDynamoDBLocal;

/**
 * テスト用に、DynamoDB Local をテストと同じプログラムの中で動かす。
 *
 * <p>ネットワークもAWSのアカウントもいらず、データはメモリに置かれるのでテストが終われば消える。
 * テストごとに別のテーブル名を使えば、テスト同士でデータが混ざらない。
 */
final class LocalDynamoDb {

    private static final AmazonDynamoDBLocal DB = DynamoDBEmbedded.create();

    private LocalDynamoDb() {
    }

    /** 新しいテーブルを作り、そこにつないだ保存先を返す。 */
    static DynamoDbStorage newStorage() {
        return new DynamoDbStorage(client(), newTable());
    }

    /** 新しいテーブルを作り、その名前を返す（保存先を通さずにテーブルを直接さわるテスト用）。 */
    static String newTable() {
        String tableName = "Test-" + UUID.randomUUID();
        MainTable.createIfNotExists(DB.dynamoDbClient(), tableName);
        return tableName;
    }

    static DynamoDbClient client() {
        return DB.dynamoDbClient();
    }
}
