/**
 * ログイン（トークンの確認）と権限の確認。
 *
 * <p>流れ:
 * <ol>
 *   <li>画面は、ログインしたときにもらったトークン（JWT）を、APIを呼ぶたびに {@code Authorization: Bearer トークン} で送る
 *   <li>Spring Security が、トークンの署名と期限を確かめる（{@link SecurityConfig}）。おかしければ401
 *   <li>コントローラーは {@link CurrentUsers#require} で「この人はこの操作をしてよいか」を確かめる。だめなら403
 * </ol>
 *
 * <p>本番は Cognito のトークン（段階4）、手元は仮ログインのトークン（{@link DevLogin}）。どちらも同じ形にしているので、
 * 2と3の処理は同じものを通る。
 */
package io.github.otksudo.fleetanalysis.app.security;
