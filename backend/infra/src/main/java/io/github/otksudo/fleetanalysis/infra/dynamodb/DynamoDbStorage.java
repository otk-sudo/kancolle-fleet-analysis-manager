package io.github.otksudo.fleetanalysis.infra.dynamodb;

import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import java.net.URI;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * DynamoDB への接続と、保存先（リポジトリ）の入り口。
 *
 * <p>app はこのクラスだけを使い、AWS SDK のクラスに直接さわらない。
 * 使い終わったら {@link #close()} で接続を閉じる（Spring が終了時に自動で呼ぶ）。
 */
public final class DynamoDbStorage implements AutoCloseable {

    private final DynamoDbClient client;
    private final ApplicationRepository applications;
    private final LotteryRepository lotteries;

    /**
     * 接続の設定。
     *
     * @param tableName   テーブル名（本番は {@code Main}）
     * @param region      AWSのリージョン（例: ap-northeast-1 = 東京）
     * @param endpoint    接続先のURL。DynamoDB Local を使うときだけ指定する（例: http://localhost:8000）。
     *                    null なら本物の AWS の DynamoDB につなぐ
     * @param createTable テーブルがなければ作るか。手元の DynamoDB Local でだけ true にする
     */
    public record Settings(String tableName, String region, String endpoint, boolean createTable) {
        public Settings {
            Objects.requireNonNull(tableName, "tableName");
            Objects.requireNonNull(region, "region");
        }
    }

    DynamoDbStorage(DynamoDbClient client, String tableName) {
        this.client = client;
        this.applications = new DynamoDbApplicationRepository(client, tableName);
        this.lotteries = new DynamoDbLotteryRepository(client, tableName);
    }

    /** 設定にしたがって DynamoDB につなぐ。 */
    public static DynamoDbStorage connect(Settings settings) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(settings.region()));
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            // DynamoDB Local はAWSの認証情報を確かめないが、SDK は何かしらの値を求めるので、ダミーの値を渡す。
            // 本物の AWS では、ここを通らず、実行環境（Lambda など）の権限が自動で使われる
            builder.endpointOverride(URI.create(settings.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        DynamoDbClient client = builder.build();
        if (settings.createTable()) {
            MainTable.createIfNotExists(client, settings.tableName());
        }
        return new DynamoDbStorage(client, settings.tableName());
    }

    public ApplicationRepository applications() {
        return applications;
    }

    public LotteryRepository lotteries() {
        return lotteries;
    }

    @Override
    public void close() {
        client.close();
    }
}
