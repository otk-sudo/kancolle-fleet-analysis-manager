package io.github.otksudo.fleetanalysis.app.config;

import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import io.github.otksudo.fleetanalysis.infra.memory.InMemoryApplicationRepository;
import io.github.otksudo.fleetanalysis.infra.memory.InMemoryLotteryRepository;
import java.time.Clock;
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

    // TODO(段階1): DynamoDB版の保存先ができたら、ここを差し替える（試作ではメモリに保存するので、再起動すると消える）
    @Bean
    public ApplicationRepository applicationRepository() {
        return new InMemoryApplicationRepository();
    }

    @Bean
    public LotteryRepository lotteryRepository() {
        return new InMemoryLotteryRepository();
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
