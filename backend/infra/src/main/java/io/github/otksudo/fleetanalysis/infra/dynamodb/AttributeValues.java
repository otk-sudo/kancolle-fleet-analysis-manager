package io.github.otksudo.fleetanalysis.infra.dynamodb;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Java の値と DynamoDB の値（{@link AttributeValue}）を行き来するための小さな道具。
 *
 * <p>DynamoDB の値には型の印がついている: S（文字列）、N（数。中身は文字列で送る）、BOOL（真偽値）、
 * L（リスト）、M（入れ子のMap）、NUL（値なし）など。Java の String や long をそのまま入れることはできないので、
 * ここで変換する。
 */
final class AttributeValues {

    private AttributeValues() {
    }

    /**
     * 日時を、文字列として並べても日時の順になる形にする（索引のソートキー用）。
     *
     * <p>{@code Instant.toString()} は秒の端数が0だと省く（例: "08:00:00Z" と "08:00:00.5Z"）ため、
     * 文字列の順に並べると前後が入れ替わることがある。ここでは常にナノ秒の9桁まで書いて、桁をそろえる。
     */
    static String sortableTime(Instant time) {
        return SORTABLE_TIME.format(time);
    }

    private static final DateTimeFormatter SORTABLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue n(double value) {
        return AttributeValue.fromN(Double.toString(value));
    }

    static AttributeValue bool(boolean value) {
        return AttributeValue.fromBool(value);
    }

    /** 値があるときだけ Map に入れる（DynamoDB では「項目がない」ことで null を表す）。 */
    static void putIfNotNull(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    static String getS(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    static long getLong(Map<String, AttributeValue> item, String name) {
        return Long.parseLong(item.get(name).n());
    }

    static double getDouble(Map<String, AttributeValue> item, String name) {
        return Double.parseDouble(item.get(name).n());
    }

    static boolean getBool(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    /**
     * フォームの回答のような「中身の型が決まっていない値」を DynamoDB の値にする。
     * 回答は文字列のほか、チェックボックスなら文字列のリストになる。
     */
    static AttributeValue fromObject(Object value) {
        if (value == null) {
            return AttributeValue.fromNul(true);
        }
        if (value instanceof String text) {
            return s(text);
        }
        if (value instanceof Boolean flag) {
            return bool(flag);
        }
        if (value instanceof Integer || value instanceof Long) {
            return n(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return AttributeValue.fromN(number.toString());
        }
        if (value instanceof List<?> list) {
            List<AttributeValue> values = new ArrayList<>();
            for (Object element : list) {
                values.add(fromObject(element));
            }
            return AttributeValue.fromL(values);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, AttributeValue> values = new LinkedHashMap<>();
            map.forEach((key, element) -> values.put(String.valueOf(key), fromObject(element)));
            return AttributeValue.fromM(values);
        }
        throw new IllegalArgumentException("保存できない型の値です: " + value.getClass().getName());
    }

    /** {@link #fromObject} の逆。数は小数点がなければ Long、あれば Double に戻す。 */
    static Object toObject(AttributeValue value) {
        return switch (value.type()) {
            case S -> value.s();
            case BOOL -> value.bool();
            case NUL -> null;
            case N -> value.n().contains(".") || value.n().contains("E") || value.n().contains("e")
                    ? (Object) Double.parseDouble(value.n())
                    : (Object) Long.parseLong(value.n());
            case L -> {
                List<Object> list = new ArrayList<>();
                for (AttributeValue element : value.l()) {
                    list.add(toObject(element));
                }
                yield list;
            }
            case M -> toObjectMap(value.m());
            default -> throw new IllegalArgumentException("読み込めない型の値です: " + value.type());
        };
    }

    static Map<String, Object> toObjectMap(Map<String, AttributeValue> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        values.forEach((key, element) -> map.put(key, toObject(element)));
        return map;
    }

    static Map<String, AttributeValue> fromObjectMap(Map<String, Object> map) {
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        map.forEach((key, element) -> values.put(key, fromObject(element)));
        return values;
    }
}
