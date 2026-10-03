package io.github.otksudo.fleetanalysis.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ほかのWebサイトからの攻撃を防ぐための確認（仕様 2章、docs/design.md 1章）。
 *
 * <p>ツールにはログインがない。代わりに、配信者さんのPCの中（127.0.0.1）でだけ待ち受けている。
 * それでも、配信者さんが同じPCのブラウザで開いたほかのWebサイトが、裏でツールに要求を送ってくることがある。
 * そこで、すべての要求について次の2つを確かめ、当てはまらなければ 403 で断る。
 * <ol>
 *   <li><b>Host ヘッダー</b>（どのアドレスあてに送ったか）が 127.0.0.1 か localhost であること。
 *       DNSリバインディング（ほかのサイトが、自分のドメイン名をこのPCの 127.0.0.1 に向け直して、
 *       ツールのデータを読む攻撃）では、Host がそのサイトのドメイン名になるので、ここで断れる
 *   <li><b>Origin ヘッダー</b>（どのサイトの画面から送ったか）が、データを変える要求（POST・PUT・PATCH・DELETE）では
 *       ツール自身であること。CSRF（ほかのサイトが、裏で削除などの命令を送る攻撃）を防ぐ。
 *       ブラウザは GET・HEAD 以外の要求に必ず Origin を付ける（https://fetch.spec.whatwg.org/#origin-header ）ので、
 *       Origin がない要求はブラウザ以外（同じPCのプログラム）からのものとして通す
 * </ol>
 *
 * <p>あわせて、すべての応答に次のヘッダーを付ける（ログインがないので、画面を勝手に使われないようにするため）。
 * <ul>
 *   <li>{@code X-Frame-Options: DENY} と {@code Content-Security-Policy: frame-ancestors 'none'}:
 *       ほかのサイトが、ツールの画面を自分のページの中（iframe）に透明にして重ね、配信者さんに削除などのボタンを
 *       押させる攻撃（クリックジャッキング）を防ぐ。ツールの画面をほかのページの中に表示させない
 *       （https://developer.mozilla.org/docs/Web/HTTP/Reference/Headers/X-Frame-Options ）
 *   <li>{@code X-Content-Type-Options: nosniff}: ブラウザが、応答の種類（JSON など）を中身から勝手に推測しないようにする
 * </ul>
 *
 * <p>「フィルター」は、Spring がどのコントローラーで処理するかを決めるより前に、すべての要求に割り込む仕組み。
 * {@code @Component} を付けると、Spring Boot が自動でフィルターとして登録する。
 * {@code OncePerRequestFilter} は、1つの要求につき1回だけ呼ばれることを保証してくれる Spring の部品。
 */
@Component
public class LocalAccessFilter extends OncePerRequestFilter {

    /** 受け付けるホスト名。ツールは 127.0.0.1 でだけ待ち受けるので、ほかの名前で届くことはふつうない */
    private static final Set<String> ALLOWED_HOST_NAMES = Set.of("127.0.0.1", "localhost");

    /** データを変えない要求。Origin は確かめない（ほかのサイトは、応答の中身を読めないため。下の説明を参照） */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final List<String> extraOrigins;

    /**
     * @param extraOrigins ツール自身のほかに受け付ける Origin（カンマ区切り）。
     *                     開発中だけ使う。画面を Vite の開発サーバー（ポート5173）から開くと、Origin がそちらになるため
     */
    public LocalAccessFilter(@Value("${app.local-access.extra-origins:}") List<String> extraOrigins) {
        this.extraOrigins = extraOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 断るときの応答にも付けるので、最初に付ける
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // getServerName() は、Host ヘッダーの「:」より前（ホスト名）を返す（Jakarta Servlet の決まり）
        if (!ALLOWED_HOST_NAMES.contains(request.getServerName().toLowerCase())) {
            reject(response, "このツールは、同じPCの http://127.0.0.1 から開いてください");
            return;
        }
        // GET などは、ほかのサイトから送られても、ブラウザがそのサイトに応答の中身を見せない（同一オリジンポリシー）。
        // ツールは CORS（ほかのサイトに中身を見せてよいと伝える仕組み）の設定をしないので、データは漏れない
        if (!SAFE_METHODS.contains(request.getMethod()) && !isAllowedOrigin(request)) {
            reject(response, "ほかのWebサイトからの操作は受け付けません");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        // ツール自身の画面から送ったときは、Origin が「http://」＋ Host ヘッダーと同じになる
        // （Host は上で 127.0.0.1 か localhost だと確かめ済み）
        String host = request.getHeader("Host");
        if (host != null && origin.equalsIgnoreCase("http://" + host)) {
            return true;
        }
        return extraOrigins.stream().anyMatch(origin::equalsIgnoreCase);
    }

    /** ほかのAPIのエラーと同じ形（{"code": ..., "message": ...}）の JSON で 403 を返す */
    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"forbidden_origin\",\"message\":\"" + message + "\"}");
    }
}
