package io.github.otksudo.fleetanalysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link XId} のテスト。
 *
 * <p>{@code @ParameterizedTest} と {@code @ValueSource} を使うと、同じテストを入力を変えて何度も実行できる。
 * {@code assertThat(実際の値).isEqualTo(期待する値)} は AssertJ の書き方で、英文のように読める。
 */
class XIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"@Teitoku_01", "teitoku_01", "  @TEITOKU_01 ", "＠teitoku_01", "＠Ｔｅｉｔｏｋｕ＿０１"})
    void 先頭の記号と大文字小文字と全角半角の違いを吸収する(String raw) {
        assertThat(XId.parse(raw)).isEqualTo(new XId("teitoku_01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "@", "teitoku-01", "this_id_is_too_long", "提督"})
    void 形式が正しくないIDは受け付けない(String raw) {
        assertThatThrownBy(() -> XId.parse(raw)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void 文字列表現は正規化後の値() {
        assertThat(XId.parse("@Abc")).hasToString("abc");
    }
}
