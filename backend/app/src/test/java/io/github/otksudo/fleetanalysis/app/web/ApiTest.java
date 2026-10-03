package io.github.otksudo.fleetanalysis.app.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.otksudo.fleetanalysis.app.security.DevLogin;
import io.github.otksudo.fleetanalysis.app.security.DevUsers;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * APIを外から呼んだときの動きを確かめるテスト。
 *
 * <p>{@code MockMvc} は、実際にWebサーバーを立てずに「HTTPリクエストを送ったつもり」でコントローラーを呼べる道具。
 * フィルター（秘密キーの確認）や例外の変換も、本物と同じ順番で通る。
 *
 * <p>{@code @DirtiesContext} は「テストごとにアプリを作り直す」指定。保存先がメモリなので、
 * 前のテストで登録した応募が次のテストに残らないようにしている。
 *
 * <p>このテストでは、すべてのリクエストに「配信者の試しユーザー」のトークンを付けて送る（{@link AsStreamer}）。
 * ログインや権限そのものの確認は {@code AuthApiTest} で行う。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiTest {

    /**
     * テストのときだけ足す部品。MockMvc で送るすべてのリクエストに、配信者のトークンを付ける。
     * {@code defaultRequest} に書いたヘッダーは、各テストのリクエストに自動で足される。
     */
    @TestConfiguration
    static class AsStreamer {

        @Bean
        MockMvcBuilderCustomizer streamerToken(DevLogin devLogin, DevUsers devUsers) {
            String token = devLogin.issue(devUsers.user("dev-streamer").orElseThrow());
            return builder -> builder.defaultRequest(get("/").header("Authorization", "Bearer " + token));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * 応募受付のJSON。受付日時は回答IDの番号の秒にする（例: s2 → 12:00:02）。
     * 同じ人の応募の「どちらが先か」で印が決まるので、受付順がはっきりするようにしている。
     */
    private static String intakeJson(String submissionId, String xId) {
        return """
                {
                  "submissionId": "%s",
                  "submittedAt": "2026-10-01T12:00:%02d+09:00",
                  "formVersion": "v1",
                  "answers": {
                    "xId": "%s",
                    "admiralName": "テスト提督",
                    "nameDisplay": "匿名希望",
                    "simulatorUrl": "https://example.com/fleet",
                    "monthlySpending": "0円",
                    "purpose": "イベント"
                  }
                }
                """.formatted(submissionId, Integer.parseInt(submissionId.replaceAll("\\D", "")), xId);
    }

    private String submit(String submissionId, String xId) throws Exception {
        String body = mockMvc.perform(post("/intake/applications")
                        .header("X-Form-Key", "dev-form-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeJson(submissionId, xId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.applicationId");
    }

    @Test
    void 秘密キーがないと受付できない() throws Exception {
        mockMvc.perform(post("/intake/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeJson("s1", "@test_user")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @ParameterizedTest
    // URLの書き方を変えても秘密キーの確認をすり抜けられないこと（%69 は i を別の書き方にしたもの、;a=b は付け足し）。
    // ;a=b を含むURLは、秘密キーの確認より前に Spring Security が「怪しいURL」として400で断る。
    // どちらで断られても受付されなければよいので、「400番台（送った側の誤り）」であることと、応募が増えていないことを確かめる
    @ValueSource(strings = {"/%69ntake/applications", "/intake;a=b/applications", "/intake/applications;a=b"})
    void URLの書き方を変えても秘密キーの確認はすり抜けられない(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeJson("s1", "@test_user")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/applications")).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void 受け付けた応募が一覧と詳細に出る() throws Exception {
        String id = submit("s1", "@Test_User");

        mockMvc.perform(get("/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].xId").value("test_user"))
                .andExpect(jsonPath("$.items[0].status").value("pending"));

        mockMvc.perform(get("/applications/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymous").value(true));
    }

    @Test
    void 同じXのIDの2件目には重複の印がつく() throws Exception {
        submit("s1", "@test_user");
        mockMvc.perform(post("/intake/applications")
                        .header("X-Form-Key", "dev-form-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intakeJson("s2", "@test_user")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flags[0].type").value("duplicate"));
    }

    @Test
    void 許されないステータス変更は409になる() throws Exception {
        String id = submit("s1", "@test_user");
        // 未着手 → 分析済み は、分析中を通らないと変えられない
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": %d, \"status\": \"done\"}".formatted(version(id))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    void 古い版での変更は409になり版がない変更は400になる() throws Exception {
        String id = submit("s1", "@test_user");
        long version = version(id);
        String body = "{\"version\": %d, \"memo\": \"%s\"}";
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(version, "1人目")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1));
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(version, "2人目")))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\": \"版なし\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_value"));
    }

    @Test
    void 見送りの理由つきで変えると履歴に残り配信日を消せる() throws Exception {
        String id = submit("s1", "@test_user");
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": %d, \"streamDate\": \"2026-10-10\"}".formatted(version(id))))
                .andExpect(jsonPath("$.streamDate").value("2026-10-10"));
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": %d, \"status\": \"skipped\", \"skipReason\": \"withdrawn\", \"clearStreamDate\": true}"
                                .formatted(version(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipReason").value("withdrawn"))
                .andExpect(jsonPath("$.streamDate").doesNotExist());

        mockMvc.perform(get("/applications/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("status"))
                .andExpect(jsonPath("$[0].from").value("pending"))
                .andExpect(jsonPath("$[0].to").value("skipped"));
    }

    @Test
    void 重複の解消とまとめての変更と検索ができる() throws Exception {
        String old = submit("s1", "@user_a");
        String resent = submit("s2", "@user_a");
        String other = submit("s3", "@user_b");

        mockMvc.perform(post("/applications/" + resent + "/keep")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": %d}".formatted(version(resent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[0].type").value("reapply"));
        mockMvc.perform(get("/applications/" + old))
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.skipReason").value("resubmitted"));

        mockMvc.perform(post("/applications/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"id\": \"%s\", \"version\": %d}, {\"id\": \"%s\", \"version\": %d}], \"status\": \"scheduled\"}"
                                .formatted(resent, version(resent), other, version(other))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(get("/applications").param("q", "@USER_B"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(other))
                .andExpect(jsonPath("$.items[0].status").value("scheduled"));
    }

    /** 応募の今の版を読む（変更するときに送るため） */
    private long version(String id) throws Exception {
        String body = mockMvc.perform(get("/applications/" + id)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.version")).longValue();
    }

    @Test
    void 存在しない応募は404になる() throws Exception {
        mockMvc.perform(get("/applications/no-such-id")).andExpect(status().isNotFound());
    }

    @Test
    void 配信用画面にはXのIDと課金額を出さない() throws Exception {
        String id = submit("s1", "@test_user");
        changeStatus(id, "analyzing");

        mockMvc.perform(get("/stream/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.displayName").value("匿名提督"))
                .andExpect(jsonPath("$.current.answers.xId").doesNotExist())
                .andExpect(jsonPath("$.current.answers.monthlySpending").doesNotExist())
                .andExpect(jsonPath("$.current.answers.admiralName").doesNotExist());
    }

    @Test
    void まとめ抽選で当選者は分析予定になり記録が残る() throws Exception {
        submit("s1", "@user_a");
        submit("s2", "@user_b");

        mockMvc.perform(post("/lotteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"bulk\", \"winners\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries", hasSize(2)));

        mockMvc.perform(get("/applications").param("status", "scheduled"))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mockMvc.perform(get("/lotteries")).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void 抽選設定を変えられ古い版での変更は409になる() throws Exception {
        String settings = "{\"kind\": \"lottery\", \"version\": %d, \"value\": "
                + "{\"enabled\": true, \"lossBonusEnabled\": false, \"lossBonusStrength\": 1.0}}";
        mockMvc.perform(put("/settings/lottery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings.formatted(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.value.lossBonusEnabled").value(false));

        mockMvc.perform(put("/settings/lottery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings.formatted(1)))
                .andExpect(status().isConflict());
    }

    @Test
    void おかしな設定値やnullの項目は400や正常な結果になる() throws Exception {
        mockMvc.perform(put("/settings/lottery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"lottery\", \"version\": 1, \"value\": "
                                + "{\"enabled\": true, \"lossBonusEnabled\": true, \"lossBonusStrength\": -1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_value"));

        submit("s1", "@user_a");
        mockMvc.perform(post("/lotteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"bulk\", \"winners\": 1, \"includeFlagged\": null}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 分析予定に変えた応募はそのグループの最後尾に入る() throws Exception {
        String a = submit("s1", "@user_a");
        String b = submit("s2", "@user_b");
        String c = submit("s3", "@user_c");
        changeStatus(a, "scheduled");
        changeStatus(b, "scheduled");
        // c を未着手の先頭へ動かす（並び順が 1000 に振り直される）
        mockMvc.perform(put("/applications/" + c + "/position")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"after\": null}"))
                .andExpect(status().isNoContent());
        changeStatus(c, "scheduled");

        mockMvc.perform(get("/applications").param("status", "scheduled"))
                .andExpect(jsonPath("$.items[0].id").value(a))
                .andExpect(jsonPath("$.items[1].id").value(b))
                .andExpect(jsonPath("$.items[2].id").value(c));
    }

    private void changeStatus(String id, String status) throws Exception {
        mockMvc.perform(patch("/applications/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": %d, \"status\": \"%s\"}".formatted(version(id), status)))
                .andExpect(status().isOk());
    }

    @Test
    void 未着手の中で並べ替えられる() throws Exception {
        String first = submit("s1", "@user_a");
        String second = submit("s2", "@user_b");
        // 1件目を2件目の後ろへ
        mockMvc.perform(put("/applications/" + first + "/position")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"after\": \"" + second + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/applications"))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first));
    }
}
