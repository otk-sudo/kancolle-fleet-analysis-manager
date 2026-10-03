package io.github.otksudo.fleetanalysis.app.web;

/**
 * 操作した人（履歴や抽選記録に「誰が」として残す名前）を決める。
 *
 * <p>まだログインの仕組みがないので、今は決まった名前を返す。
 * TODO(段階3): ログインができたら、ログインしている人の名前を返す
 */
final class Actors {

    /** ログインができるまで使う名前 */
    static final String BEFORE_LOGIN = "試作ユーザー";

    private Actors() {
    }

    static String current() {
        return BEFORE_LOGIN;
    }
}
