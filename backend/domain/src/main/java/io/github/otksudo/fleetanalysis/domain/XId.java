package io.github.otksudo.fleetanalysis.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * XのID。重複判定と履歴の紐づけに使う（仕様 5.2）。
 *
 * <p>同じ人でも「@Teitoku」「teitoku」のように書き方が揺れるため、先頭の@を除き、小文字にそろえて保存する。
 *
 * <p>{@code record} はJava 16からの書き方で、値を入れるだけのクラスを短く書ける。
 * フィールド（ここでは value）、コンストラクタ、equals（中身が同じなら等しいとみなす）などが自動で作られる。
 * 作ったあとに中身を変えられない（不変）ので、うっかり書き換えてしまう心配がない。
 *
 * @param value 正規化済みのID（@なし、小文字、英数字とアンダースコアのみ、1〜15文字）
 */
public record XId(String value) {

    // Xのユーザー名のルール: 英数字とアンダースコアで1〜15文字
    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,15}$");

    /**
     * コンストラクタ（recordでは引数の括弧を省略した形で書ける）。
     * 形式が正しくない値でXIdが作られないよう、ここでチェックする。
     */
    public XId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new InvalidValueException("XのIDの形式が正しくありません: " + value);
        }
    }

    /**
     * フォームに入力された文字列を正規化してXIdにする。
     *
     * @param raw 入力された文字列（例: " @Teitoku_01 "）
     * @return 正規化したXId（例: teitoku_01）
     * @throws InvalidValueException 形式が正しくない場合
     */
    public static XId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        // NFKC正規化: 全角の英数字・記号を半角にそろえる（例: ＠Ｔｅｉｔｏｋｕ＿０１ → @Teitoku_01）。
        // 日本語入力のまま打つ人がいるため
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        String trimmed = normalized.strip(); // 前後の空白を除く
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        // Locale.ROOT: 実行環境の言語設定に左右されずに小文字化する（トルコ語などでの誤変換を防ぐ）
        return new XId(trimmed.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
