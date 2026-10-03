package io.github.otksudo.fleetanalysis.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.otksudo.fleetanalysis.infra.sqlite.SqliteStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 保存先を SQLite にしたとき（ふだんの形）に、ツールが起動してデータをファイルに残せるかを確かめるテスト。
 *
 * <p>ほかのテストは保存先をメモリにしている（src/test/resources/config/application.yml）ので、
 * ここだけ {@code app.storage} を sqlite に戻し、データのフォルダをテスト用の一時フォルダにする。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SqliteStorageConfigTest {

    /** JUnit がテストの前に作り、終わったら消してくれる一時フォルダ */
    @TempDir
    static Path dataDir;

    /** 設定の値を、テストを動かすときに決める（一時フォルダの場所は、動かすまでわからないため） */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage", () -> "sqlite");
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void データのフォルダにファイルを作り_変えた設定がファイルに残る() throws Exception {
        Path file = dataDir.resolve(ServiceConfig.SqliteStorageConfig.DATABASE_FILE);
        assertThat(file).exists();

        mockMvc.perform(put("/settings/lottery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"lottery\", \"version\": 1, \"value\": "
                                + "{\"enabled\": false, \"lossBonusEnabled\": true, \"lossBonusStrength\": 2.0}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/settings/lottery")).andExpect(jsonPath("$.value.enabled").value(false));

        // ツールを止めて起動し直したときと同じように、同じファイルを開き直しても設定が残っている
        assertThat(Files.size(file)).isPositive();
        assertThat(SqliteStorage.open(file).lotteries().loadSettings().settings().lossBonusStrength()).isEqualTo(2.0);
    }
}
