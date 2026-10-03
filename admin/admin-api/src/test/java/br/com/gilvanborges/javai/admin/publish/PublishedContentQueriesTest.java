package br.com.gilvanborges.javai.admin.publish;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 02, teste 11: nenhuma consulta da publicação seleciona campos privados ou ignora o filtro. */
class PublishedContentQueriesTest {

    @Test
    void nenhuma_consulta_le_campos_privados() {
        assertThat(queries()).isNotEmpty().allSatisfy(sql -> {
            assertThat(sql).doesNotContain("private_notes");
            assertThat(sql).doesNotContainIgnoringCase("select *");
        });
    }

    @Test
    void toda_consulta_filtra_publicado_e_publico() {
        assertThat(queries()).allSatisfy(sql ->
                assertThat(sql).contains("WHERE status = 'published' AND visibility = 'public'"));
    }

    private static List<String> queries() {
        return Arrays.stream(JdbcPublishedContentReader.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(PublishedContentQueriesTest::value)
                .toList();
    }

    private static String value(Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
