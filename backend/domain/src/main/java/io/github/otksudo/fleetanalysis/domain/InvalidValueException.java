package io.github.otksudo.fleetanalysis.domain;

/**
 * 入力された値が業務ルールに合わないときの例外（例: XのIDの形式が正しくない）。
 *
 * <p>開発ルール 3章「業務上のエラーは専用の例外クラスにする」に従った例外。
 * API層（app）でこの例外を受け取り、400（入力の誤り）の {@code ApiError} に変換して返す（段階1で実装）。
 * プログラムの使い方の誤り（負の人数を渡したなど）には、Java標準の {@link IllegalArgumentException} を使う。
 *
 * <p>{@link RuntimeException} を継承しているので、メソッドに {@code throws} を書かなくても投げられる（非検査例外）。
 */
public class InvalidValueException extends RuntimeException {

    public InvalidValueException(String message) {
        super(message);
    }
}
