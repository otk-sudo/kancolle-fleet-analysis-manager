package io.github.otksudo.fleetanalysis.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 応募受付API（/intake/...）に、フォーム用の秘密キー（X-Form-Key ヘッダー）が付いているかを確かめる（仕様 3章）。
 *
 * <p>「フィルター」は、リクエストがコントローラーに届く前に必ず通る関所のようなもの。
 * キーが合わなければ、コントローラーを呼ばずに 401 を返す。
 * {@code OncePerRequestFilter} は Spring が用意している「1回のリクエストで1回だけ動くフィルター」のひな形。
 */
@Component
public class FormKeyFilter extends OncePerRequestFilter {

    private final byte[] expectedKey;

    /**
     * {@code @Value} は設定ファイル（application.yml）や環境変数から値を読み込む目印。
     * 秘密キーはコードに書かず、環境変数 FORM_KEY で渡す。
     */
    public FormKeyFilter(@Value("${app.form-key}") String formKey) {
        this.expectedKey = formKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 受付API以外（管理画面用のAPI）はこのフィルターの対象外
        return !request.getRequestURI().startsWith("/intake/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String actual = request.getHeader("X-Form-Key");
        // MessageDigest.isEqual は、どこまで一致したかで処理時間が変わらない比べ方。
        // 普通の equals だと、応答時間の差からキーを1文字ずつ推測される恐れがあるため
        if (actual != null && MessageDigest.isEqual(expectedKey, actual.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"フォーム用の秘密キーが正しくありません\"}");
    }
}
