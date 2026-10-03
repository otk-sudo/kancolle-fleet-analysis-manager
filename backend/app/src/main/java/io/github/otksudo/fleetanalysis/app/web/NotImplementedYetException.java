package io.github.otksudo.fleetanalysis.app.web;

/** 試作の段階ではまだ作っていない機能が呼ばれたときの例外。APIでは 501 Not Implemented を返す。 */
public class NotImplementedYetException extends RuntimeException {

    public NotImplementedYetException(String message) {
        super(message);
    }
}
