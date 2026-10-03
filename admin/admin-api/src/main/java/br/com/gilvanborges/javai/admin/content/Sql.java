package br.com.gilvanborges.javai.admin.content;

import org.springframework.jdbc.support.SqlArrayValue;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Conversões entre os records do admin e as colunas do PostgreSQL. */
public final class Sql {

    private Sql() {
    }

    /** Texto vazio não existe no banco: vira NULL, e os CHECKs de tamanho começam em 1. */
    public static String text(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Lista para {@code text[]}. O {@link SqlArrayValue} evita que o Spring expanda o array em vários parâmetros. */
    public static SqlArrayValue textArray(List<String> values) {
        Object[] cleaned = values == null ? new Object[0]
                : values.stream().map(Sql::text).filter(Objects::nonNull).toArray();
        return new SqlArrayValue("text", cleaned);
    }

    public static List<String> list(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    public static <I extends ContentInput> ContentItem<I> item(ResultSet rs, I data) throws SQLException {
        return new ContentItem<>(
                rs.getObject("id", UUID.class),
                data,
                ContentStatus.fromDb(rs.getString("status")),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("updated_by"));
    }
}
