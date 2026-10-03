package io.github.otksudo.fleetanalysis.domain;

/**
 * 今の状態ではその操作ができないときの例外。API層で409（競合）に変換する。
 *
 * <p>例: 「分析済み」から「未着手」へは戻せない、抽選がオフになっている、設定の版が古い。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
