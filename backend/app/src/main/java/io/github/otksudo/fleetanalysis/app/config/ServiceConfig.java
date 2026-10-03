package io.github.otksudo.fleetanalysis.app.config;

import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import io.github.otksudo.fleetanalysis.infra.dynamodb.DynamoDbStorage;
import io.github.otksudo.fleetanalysis.infra.memory.InMemoryApplicationRepository;
import io.github.otksudo.fleetanalysis.infra.memory.InMemoryLotteryRepository;
import io.github.otksudo.fleetanalysis.infra.memory.InMemoryStreamOperatorRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 部品（Bean）の組み立て方を書いた設定クラス。
 *
 * <p>{@code @Configuration} は「このクラスに部品の作り方が書いてある」という目印。
 * {@code @Bean} を付けたメソッドの戻り値を Spring が1つだけ作って保管し、必要なクラス（コントローラーなど）に渡してくれる。
 *
 * <p>domain と infra のクラスは Spring に依存させない方針（docs/development.md 3章）なので、
 * それらには {@code @Component} などを付けず、ここで new して Spring に登録する。
 */
@Configuration
public class ServiceConfig {

    /** 現在時刻の取り出し口。テストでは固定の時計に差し替えられるよう、Beanにしておく */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 保存先をメモリにするときの部品（設定 {@code app.storage: memory}。試作・テスト用）。
     *
     * <p>{@code @ConditionalOnProperty} は「設定がこの値のときだけ、このクラスの部品を作る」という目印。
     * 設定がないときもこちらを使う（matchIfMissing = true）。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.storage", havingValue = "memory", matchIfMissing = true)
    static class InMemoryStorageConfig {

        @Bean
        ApplicationRepository applicationRepository() {
            return new InMemoryApplicationRepository();
        }

        @Bean
        LotteryRepository lotteryRepository() {
            return new InMemoryLotteryRepository();
        }

        @Bean
        StreamOperatorRepository streamOperatorRepository() {
            return new InMemoryStreamOperatorRepository();
        }
    }

    /**
     * 保存先を DynamoDB にするときの部品（設定 {@code app.storage: dynamodb}。本番と、手元の DynamoDB Local 用）。
     *
     * <p>接続（DynamoDbStorage）は close() を持つので、Spring がアプリの終了時に自動で閉じてくれる。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.storage", havingValue = "dynamodb")
    static class DynamoDbStorageConfig {

        @Bean
        DynamoDbStorage dynamoDbStorage(
                @Value("${app.dynamodb.table-name}") String tableName,
                @Value("${app.dynamodb.region}") String region,
                @Value("${app.dynamodb.endpoint:}") String endpoint,
                @Value("${app.dynamodb.create-table:false}") boolean createTable) {
            return DynamoDbStorage.connect(new DynamoDbStorage.Settings(tableName, region, endpoint, createTable));
        }

        @Bean
        ApplicationRepository applicationRepository(DynamoDbStorage storage) {
            return storage.applications();
        }

        @Bean
        LotteryRepository lotteryRepository(DynamoDbStorage storage) {
            return storage.lotteries();
        }

        @Bean
        StreamOperatorRepository streamOperatorRepository(DynamoDbStorage storage) {
            return storage.streamOperators();
        }
    }

    // メソッドの引数に書いた部品は、Spring が上で作ったものを渡してくれる（依存性の注入）
    @Bean
    public ApplicationService applicationService(ApplicationRepository applicationRepository, Clock clock) {
        return new ApplicationService(applicationRepository, clock);
    }

    @Bean
    public LotteryService lotteryService(
            ApplicationRepository applicationRepository, LotteryRepository lotteryRepository, Clock clock) {
        return new LotteryService(applicationRepository, lotteryRepository, clock);
    }
}
