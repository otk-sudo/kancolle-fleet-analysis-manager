package io.github.otksudo.fleetanalysis.infra.sqlite;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java の値と、SQLite の列に入れる値の変換をまとめたもの。
 *
 * <p>SQLite には日時や「形の決まっていない値（Map）」の型がないので、どちらも文字（TEXT）にして入れる。
 */
final class SqliteValues {

    /**
     * 日時を文字にするときの形。秒の端数を必ず9桁にする（例: 2026-10-01T10:00:00.500000000Z）。
     *
     * <p>{@code Instant.toString()} は端数がないと「10:00:00Z」、あると「10:00:00.5Z」のように長さが変わる。
     * そのまま文字の順に並べると「10:00:00.5Z」が「10:00:00Z」より前になってしまい、ORDER BY で日時の順にならない。
     * 長さをそろえれば、文字の順と日時の順が同じになる。
     */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * JSON の読み書きをする道具（Jackson）。
     * USE_LONG_FOR_INTS: JSON の整数は、大きさによらずいつも Java の Long で読む（Integer と Long が混ざらないように）
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private SqliteValues() {
    }

    static String time(Instant instant) {
        return TIME.format(instant);
    }

    static Instant time(String text) {
        return Instant.parse(text);
    }

    /** 値（Map や List）を JSON の文字にする */
    static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** JSON の文字を Map に戻す（キーの順番は保存したときのまま） */
    static Map<String, Object> jsonToMap(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    /** JSON の文字を、Map の List に戻す */
    static List<Map<String, Object>> jsonToList(String json) {
        return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
    }

    /** 真偽を SQLite の整数（1 = true、0 = false）にする */
    static int bool(boolean value) {
        return value ? 1 : 0;
    }
}
