package io.github.otksudo.fleetanalysis.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ほかのWebサイトからの攻撃を防ぐ確認（{@link LocalAccessFilter}）のテスト。
 *
 * <p>MockMvc では、ヘッダーを自由に付けて「ほかのサイトから送られた要求」を作れる。
 * 開発中だけ受け付ける Origin として、http://localhost:5173 を設定して起動する（properties の指定）。
 */
@SpringBootTest(properties = "app.local-access.extra-origins=http://localhost:5173")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LocalAccessFilterTest {

    @Autowired
    private MockMvc mockMvc;

    /** 抽選設定を変える要求（データを変える要求の例）。中身は正しいので、確認を通れば 200 になる */
    private static MockHttpServletRequestBuilder changeSettings() {
        return put("/settings/lottery")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\": \"lottery\", \"version\": 1, \"value\": "
                        + "{\"enabled\": true, \"lossBonusEnabled\": true, \"lossBonusStrength\": 1.0}}");
    }

    @Test
    void 自分のPCのアドレスあての要求は通る() throws Exception {
        mockMvc.perform(get("/applications").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mockMvc.perform(get("/applications").header("Host", "localhost:8080")).andExpect(status().isOk());
    }

    @Test
    void ホスト名の大文字と小文字は区別しない() throws Exception {
        mockMvc.perform(get("/applications").header("Host", "LOCALHOST:8080")).andExpect(status().isOk());
    }

    @Test
    void ほかのドメイン名あての要求は断る() throws Exception {
        // DNSリバインディングでは、ブラウザはほかのサイトのドメイン名を Host に入れて送ってくる
        mockMvc.perform(get("/applications").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden_origin"));
        // 最後に . を付けた名前（localhost.）も、別の名前として断る
        mockMvc.perform(get("/applications").header("Host", "localhost.:8080")).andExpect(status().isForbidden());
    }

    @Test
    void 読むだけの要求はOriginがほかのサイトでも通る() throws Exception {
        // ほかのサイトは応答の中身を読めない（同一オリジンポリシー）ので、GET は Origin を確かめない
        mockMvc.perform(get("/applications").header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isOk());
    }

    @Test
    void ほかのサイトからの削除と一部変更も断る() throws Exception {
        mockMvc.perform(delete("/applicants/test_user")
                        .header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/applications/no-such-id")
                        .header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 画面をほかのページの中に表示させないヘッダーを付ける() throws Exception {
        mockMvc.perform(get("/applications").header("Host", "127.0.0.1:8080"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // 断ったときの応答にも付く
        mockMvc.perform(get("/applications").header("Host", "evil.example"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void ツール自身の画面からの変更は通る() throws Exception {
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080").header("Origin", "http://127.0.0.1:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void ほかのサイトの画面からの変更は断る() throws Exception {
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden_origin"));
        // ポートが違えば、同じPCでも別のサイト（別のアプリ）として扱う
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080").header("Origin", "http://127.0.0.1:9999"))
                .andExpect(status().isForbidden());
        // サイトがわからないとき、ブラウザは Origin を null にする
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080").header("Origin", "null"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 設定で足したOriginからの変更は通る() throws Exception {
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk());
    }

    @Test
    void Originがない変更はブラウザ以外からとして通る() throws Exception {
        mockMvc.perform(changeSettings().header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
    }
}
