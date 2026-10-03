package io.github.otksudo.fleetanalysis.app.security;

import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorRepository;
import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorService;
import io.github.otksudo.fleetanalysis.domain.auth.UserDirectory;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ログイン（トークンの確認）の設定。Spring Security という、Webアプリの認証・認可を受け持つライブラリを使う。
 *
 * <p>ここで決めていること:
 * <ul>
 *   <li>応募受付（/intake/...）はトークンなしで通す（代わりにフォーム用の秘密キーを {@code FormKeyInterceptor} で確かめる）
 *   <li>仮ログイン（/dev/...）はトークンなしで通す（ログインする前に呼ぶため。手元用の設定のときだけ存在する）
 *   <li>それ以外のAPIは、正しいトークンがなければ401にする
 * </ul>
 * 「誰が何をしてよいか」（403）は、各コントローラーで {@link CurrentUsers#require} を使って確かめる。
 */
@Configuration
public class SecurityConfig {

    /**
     * どのURLにトークンが必要かなどを決める「フィルターの並び」。
     * リクエストはコントローラーに届く前に、ここで決めた順にチェックを通る。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> writeError(response,
                HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                "ログインしてください（ログインの期限が切れたときも、ログインし直してください）");
        http
                // CSRF 対策は、ブラウザが自動で送る Cookie でログインを保つ仕組み向けのもの。
                // このAPIは Cookie を使わず、画面が毎回トークンをヘッダーに付けて送るので、使わない
                .csrf(csrf -> csrf.disable())
                // サーバー側にログインの状態（セッション）を持たない。毎回トークンだけで判断する（Lambda で動かすため）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/intake/**", "/dev/**").permitAll()
                        // エラーの応答を作る内部の転送先（/error）。ここにトークンを求めると、ログインのいらないAPI（受付など）で
                        // サーバーのエラーが起きたときも「ログインしてください」(401)になり、原因がわからなくなるため通す
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // 「Authorization: Bearer トークン」を受け取り、jwtDecoder で署名と期限を確かめる
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(unauthorized))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    /** エラーを、ほかのAPIのエラーと同じ形（{"code": ..., "message": ...}）のJSONで返す */
    static void writeError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Bean
    StreamOperatorService streamOperatorService(StreamOperatorRepository repository, UserDirectory directory) {
        return new StreamOperatorService(repository, directory);
    }

    @Bean
    CurrentUsers currentUsers(StreamOperatorService streamOperators) {
        return new CurrentUsers(streamOperators);
    }

    /**
     * 手元用（設定 {@code app.auth.mode: dev}）: 仮ログインのトークンを使う。
     * demo プロファイル（scripts/dev.sh で起動したとき）とテストでだけ、この設定にしている。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.auth.mode", havingValue = "dev")
    static class DevAuthConfig {

        @Bean
        DevLogin devLogin(Clock clock) {
            return new DevLogin(clock);
        }

        @Bean
        DevUsers devUsers() {
            return new DevUsers();
        }

        @Bean
        JwtDecoder jwtDecoder(DevLogin devLogin) {
            return devLogin.decoder();
        }
    }

    /**
     * 本番用（設定 {@code app.auth.mode: cognito}。設定がないときもこちら）: Cognito のトークンを使う。
     *
     * <p>Cognito のユーザープールは段階4で AWS に作るので、今は起動できないようにしておく。
     * 何も設定しないで起動したときに、うっかり仮ログイン（誰でも運営になれる）で動いてしまわないようにするため。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.auth.mode", havingValue = "cognito", matchIfMissing = true)
    static class CognitoAuthConfig {

        // TODO(段階4): Cognito のユーザープールの発行元（issuer）から公開の鍵を読み、トークンを確かめる。名簿も Cognito から読む
        @Bean
        JwtDecoder jwtDecoder() {
            throw new IllegalStateException("Cognito でのログインは段階4で作ります。手元で動かすときは demo プロファイル"
                    + "（scripts/dev.sh）で起動するか、設定 app.auth.mode を dev にしてください");
        }
    }
}
