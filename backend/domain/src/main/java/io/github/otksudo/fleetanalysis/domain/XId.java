package io.github.otksudo.fleetanalysis.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * XのID。重複判定と履歴の紐づけに使うため、先頭の@を除いて小文字にそろえる。
 */
public record XId(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,15}$");

    public XId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("XのIDの形式が正しくありません: " + value);
        }
    }

    /** フォームに入力された文字列を正規化してXIdにする。 */
    public static XId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.strip();
        if (trimmed.startsWith("@") || trimmed.startsWith("＠")) {
            trimmed = trimmed.substring(1);
        }
        return new XId(trimmed.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
