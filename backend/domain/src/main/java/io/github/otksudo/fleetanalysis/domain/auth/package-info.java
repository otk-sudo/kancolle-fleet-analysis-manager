/**
 * ログインしている人の役割と、役割ごとにできること（権限）の決まり（仕様 2章）。
 *
 * <p>ログインの仕組み（Cognito や手元の仮ログイン）には依存しない。app がトークンから {@link CurrentUser} を作り、
 * ここの {@link AccessPolicy} で「この人はこの操作をしてよいか」を決める。
 */
package io.github.otksudo.fleetanalysis.domain.auth;
