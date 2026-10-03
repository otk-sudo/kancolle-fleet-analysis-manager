package io.github.otksudo.fleetanalysis.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ログイン（トークンの確認、401）と、役割ごとの権限（403）のテスト（仕様 2章）。
 *
 * <p>トークンは本物と同じ流れで、仮ログインのAPI（/dev/login）からもらう。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    /** 仮ログインして「Bearer トークン」の形にする */
    private String login(String userId) throws Exception {
        String body = mockMvc.perform(post("/dev/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": \"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    /** フォームから応募が1件届いたことにして、応募IDを返す */
    private String submit() throws Exception {
        String body = mockMvc.perform(post("/intake/applications")
                        // 応募受付はトークンなしで、フォーム用の秘密キーだけで受け付ける
                        .header("X-Form-Key", "dev-form-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "submissionId": "s1",
                                  "submittedAt": "2026-10-01T12:00:00+09:00",
                                  "formVersion": "v1",
                                  "answers": {"xId": "@auth_test", "admiralName": "テスト提督", "nameDisplay": "提督名でOK",
                                              "simulatorUrl": "https://example.com/fleet"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.applicationId");
    }

    @Test
    void トークンがなければ401() throws Exception {
        mockMvc.perform(get("/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void でたらめなトークンは401() throws Exception {
        mockMvc.perform(get("/applications").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 仮ログインで選べる人の一覧が見られる_いない人ではログインできない() throws Exception {
        mockMvc.perform(get("/dev/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem("dev-streamer")));
        mockMvc.perform(post("/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"userId\": \"nobody\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 自分の名前と役割とできることがわかる() throws Exception {
        mockMvc.perform(get("/me").header("Authorization", login("dev-streamer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("streamer"))
                .andExpect(jsonPath("$.displayName").value("配信者の試しユーザー"))
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.permissions", hasItem("editApplications")))
                .andExpect(jsonPath("$.permissions", not(hasItem("deleteApplicant"))));
    }

    @Test
    void 関係者は見られるが変更できない() throws Exception {
        String id = submit();
        String staff = login("dev-staff-a");
        mockMvc.perform(get("/applications/" + id).header("Authorization", staff)).andExpect(status().isOk());
        mockMvc.perform(patch("/applications/" + id).header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1, \"memo\": \"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/lotteries").header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"bulk\", \"winners\": 1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 配信者が許可した関係者だけが配信の操作をできる() throws Exception {
        String streamer = login("dev-streamer");
        String staff = login("dev-staff-a");
        mockMvc.perform(post("/stream/next").header("Authorization", staff)).andExpect(status().isForbidden());
        mockMvc.perform(post("/lotteries").header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"live\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/stream/operators/dev-staff-a").header("Authorization", streamer))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/stream/operators").header("Authorization", streamer))
                .andExpect(jsonPath("$[?(@.userId == 'dev-staff-a')].allowed").value(true))
                .andExpect(jsonPath("$[?(@.userId == 'dev-staff-b')].allowed").value(false));
        mockMvc.perform(get("/me").header("Authorization", staff))
                .andExpect(jsonPath("$.streamOperator").value(true))
                .andExpect(jsonPath("$.permissions", hasItem("streamOperation")));
        mockMvc.perform(post("/stream/next").header("Authorization", staff)).andExpect(status().isOk());
        // 配信中の抽選も「配信の操作」なので、権限の確認は通る（抽選がオフなどの理由で断られることはある）
        mockMvc.perform(post("/lotteries").header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"live\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        // 配信の操作の許可は、まとめ抽選には広がらない（仕様 2章）
        mockMvc.perform(post("/lotteries").header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"bulk\", \"winners\": 1}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/stream/operators/dev-staff-a").header("Authorization", streamer))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/stream/next").header("Authorization", staff)).andExpect(status().isForbidden());
    }

    @Test
    void 関係者は配信の操作の許可を変えられない_関係者以外には許可できない() throws Exception {
        mockMvc.perform(put("/stream/operators/dev-staff-b").header("Authorization", login("dev-staff-a")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/stream/operators/dev-admin").header("Authorization", login("dev-streamer")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 二段階認証を設定していない配信者は管理機能を使えない() throws Exception {
        String noMfa = login("dev-streamer-no-mfa");
        mockMvc.perform(get("/applications").header("Authorization", noMfa))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("二段階認証")));
        // 自分の状態は見られる（画面が「二段階認証を設定してください」と出すため）
        mockMvc.perform(get("/me").header("Authorization", noMfa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void 削除依頼への対応は運営だけ() throws Exception {
        submit();
        mockMvc.perform(delete("/applicants/auth_test").header("Authorization", login("dev-streamer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/applicants/auth_test").header("Authorization", login("dev-admin")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 変更の履歴にはログインしている人の名前が残る() throws Exception {
        String id = submit();
        mockMvc.perform(patch("/applications/" + id).header("Authorization", login("dev-streamer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1, \"status\": \"scheduled\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/applications/" + id + "/history").header("Authorization", login("dev-admin")))
                .andExpect(jsonPath("$[0].actor").value("配信者の試しユーザー"));
    }
}
