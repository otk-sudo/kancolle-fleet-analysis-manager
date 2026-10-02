package io.github.otksudo.fleetanalysis.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * アプリ全体が起動できるかの確認。
 *
 * <p>{@code @SpringBootTest} を付けると、テストの前に本物と同じようにアプリを組み立てる。
 * 設定の誤りや部品の組み合わせのミスがあると、ここで起動に失敗してテストが落ちる。
 */
@SpringBootTest
class FleetAnalysisApplicationTest {

    @Test
    void アプリケーションが起動できる() {
        // 中身は空でよい。起動できた時点で成功
    }
}
