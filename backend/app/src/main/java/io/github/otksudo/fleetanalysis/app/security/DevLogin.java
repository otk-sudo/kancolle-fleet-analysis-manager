package io.github.otksudo.fleetanalysis.app.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * 手元で動かすときだけ使う仮ログイン。トークン（JWT）を作る係と、確かめる係を持つ。
 *
 * <p>本物の Cognito は、Cognito だけが持つ秘密の鍵でトークンに署名し、サーバーは公開されている鍵で署名を確かめる。
 * 仮ログインも同じ仕組みにするため、起動のたびに鍵のペア（秘密の鍵と公開の鍵）を作り、
 * 秘密の鍵で署名し、公開の鍵で確かめる。鍵はメモリにだけ置くので、再起動すると前のトークンは使えなくなる
 * （画面はログインし直しになる）。
 *
 * <p>本番で使われないよう、設定 {@code app.auth.mode} が {@code dev} のときだけ作る（{@link SecurityConfig}）。
 */
public final class DevLogin {

    /** トークンの発行元の名前。確かめるときに、この名前で発行されたトークンだけを受け付ける */
    static final String ISSUER = "fleet-analysis-dev-login";

    /** トークンの有効期間。切れたらログインし直す */
    static final Duration LIFETIME = Duration.ofHours(12);

    private final NimbusJwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    public DevLogin(Clock clock) {
        this.clock = clock;
        KeyPair keyPair = newKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAKey key = new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) keyPair.getPrivate()).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        // 確かめること: 期限が切れていないか（JwtTimestampValidator）、仮ログインが発行したものか（JwtIssuerValidator）
        nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(ISSUER)));
        this.decoder = nimbusDecoder;
    }

    /** トークンを確かめる係（Spring Security が使う） */
    public JwtDecoder decoder() {
        return decoder;
    }

    /** この人としてログインしたことにして、トークンを作る */
    public String issue(DevUsers.DevUser user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.id())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .claim(TokenClaims.NAME, user.displayName())
                // Cognito と同じく、役割はグループの一覧として入れる
                .claim(TokenClaims.GROUPS, List.of(user.role().code()))
                .claim(TokenClaims.DEV_MFA, user.mfaConfigured())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA の鍵を作れませんでした", e);
        }
    }
}
