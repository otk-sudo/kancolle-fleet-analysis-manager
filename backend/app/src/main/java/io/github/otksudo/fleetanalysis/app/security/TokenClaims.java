package io.github.otksudo.fleetanalysis.app.security;

/**
 * トークン（JWT）の中の項目（クレーム）の名前。
 *
 * <p>JWT は「誰が」「いつまで有効か」などの項目を JSON で入れ、改ざんできないよう署名したもの。
 * 項目の名前は Cognito のトークンに合わせている（手元の仮ログインのトークンも同じ名前にする）。
 * Cognito の項目名は、段階4で本物のトークンを見て確かめる（未確認）。
 */
final class TokenClaims {

    /** ユーザーID（JWT の標準の項目。Cognito では変わらないID） */
    static final String SUBJECT = "sub";
    /** 表示名 */
    static final String NAME = "name";
    /** 属しているグループ（役割）の一覧。Cognito がこの名前で入れる */
    static final String GROUPS = "cognito:groups";
    /** 二段階認証を設定済みか。手元の仮ログインのトークンにだけ入れる（本番の確かめ方は段階4で決める） */
    static final String DEV_MFA = "dev:mfa";

    private TokenClaims() {
    }
}
