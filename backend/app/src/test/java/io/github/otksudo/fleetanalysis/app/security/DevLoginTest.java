package io.github.otksudo.fleetanalysis.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.otksudo.fleetanalysis.domain.auth.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 仮ログインのトークンの発行と確認のテスト。
 * 期限切れや、ほかの鍵で作られたトークンを受け付けないことを確かめる（受け付けると、なりすましができてしまうため）。
 */
class DevLoginTest {

    private static final DevUsers.DevUser STREAMER =
            new DevUsers.DevUser("dev-streamer", "配信者の試しユーザー", Role.STREAMER, true);

    @Test
    void 発行したトークンは確かめられる() {
        DevLogin login = new DevLogin(Clock.systemUTC());
        assertThat(login.decoder().decode(login.issue(STREAMER)).getSubject()).isEqualTo("dev-streamer");
    }

    @Test
    void 期限が切れたトークンは受け付けない() {
        // 13時間前に発行したことにする（有効期間は12時間）
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(13)), ZoneOffset.UTC);
        DevLogin login = new DevLogin(past);
        String token = login.issue(STREAMER);
        // 確かめるときは今の時刻と比べるので、同じ鍵でも期限切れで断られる
        assertThatThrownBy(() -> login.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void ほかの鍵で作られたトークンは受け付けない() {
        // バックエンドを再起動すると鍵が作り直されるので、前のトークンは使えなくなる
        String token = new DevLogin(Clock.systemUTC()).issue(STREAMER);
        assertThatThrownBy(() -> new DevLogin(Clock.systemUTC()).decoder().decode(token))
                .isInstanceOf(JwtException.class);
    }
}
