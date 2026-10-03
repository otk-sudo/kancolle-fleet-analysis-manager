package io.github.otksudo.fleetanalysis.domain;

/** ログインしている人に、その操作をする権限がないときの例外。API層で403に変換する。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
