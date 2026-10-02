package io.github.otksudo.fleetanalysis.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 応募受付API（/intake/...）に、フォーム用の秘密キー（X-Form-Key ヘッダー）が付いているかを確かめる（仕様 3章）。
 *
 * <p>「インターセプター」は、Spring が「このリクエストはどのコントローラーで処理するか」を決めた後、
 * コントローラーを呼ぶ直前に割り込む仕組み。どのURLに使うかは {@link WebConfig} で指定する。
 *
 * <p>最初はリクエストのURLの文字列を自分で見て判定する「フィルター」で作っていたが、
 * {@code /%69ntake/applications}（i を別の書き方にしたもの）のようなURLだと判定をすり抜けてしまった。
 * インターセプターなら Spring がURLを解釈した結果で対象を決めるので、URLの書き方を変えてもすり抜けられない。
 */
@Component
public class FormKeyInterceptor implements HandlerInterceptor {

    private final byte[] expectedKey;

    /**
     * {@code @Value} は設定ファイル（application.yml）や環境変数から値を読み込む目印。
     * 秘密キーはコードに書かず、環境変数 FORM_KEY で渡す。
     */
    public FormKeyInterceptor(@Value("${app.form-key}") String formKey) {
        this.expectedKey = formKey.getBytes(StandardCharsets.UTF_8);
    }

    /** コントローラーの前に呼ばれる。true を返すと先へ進み、false を返すとここで止める */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String actual = request.getHeader("X-Form-Key");
        // MessageDigest.isEqual は、どこまで一致したかで処理時間が変わらない比べ方。
        // 普通の equals だと、応答時間の差からキーを1文字ずつ推測される恐れがあるため
        if (actual != null && MessageDigest.isEqual(expectedKey, actual.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"フォーム用の秘密キーが正しくありません\"}");
        return false;
    }
}
