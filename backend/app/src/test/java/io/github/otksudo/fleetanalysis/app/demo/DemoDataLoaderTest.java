package io.github.otksudo.fleetanalysis.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * demo プロファイルで起動すると、サンプル応募が入ることを確かめる。
 * サンプルの登録中に許されないステータス変更などがあると、起動に失敗してこのテストが落ちる。
 *
 * <p>{@code @ActiveProfiles("demo")} は、テストのときだけ demo プロファイルを有効にする指定。
 */
@SpringBootTest
@ActiveProfiles("demo")
class DemoDataLoaderTest {

    @Autowired
    private ApplicationService applicationService;

    @Test
    void サンプル応募が登録され配信用画面に1人出る() {
        assertThat(applicationService.list(List.of(), null, null, "queue")).hasSize(14);
        assertThat(applicationService.streamView()).isPresent();
        assertThat(applicationService.streamView().get().previous()).isNotNull();
    }
}
