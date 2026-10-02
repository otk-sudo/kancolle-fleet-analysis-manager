package io.github.otksudo.fleetanalysis.domain;

/** 指定されたデータ（応募など）が見つからないときの例外。API層で404に変換する。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
