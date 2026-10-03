package br.com.gilvanborges.javai.ai.migrations;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DatabaseSettingsTest {

    private static Map<String, String> env() {
        var env = new HashMap<String, String>();
        env.put("AI_DB_HOST", "postgres-ai");
        env.put("POSTGRES_PASSWORD", "superuser-password-0123456789");
        env.put("AI_OWNER_PASSWORD", "owner-password-0123456789xx");
        env.put("AI_INDEXER_PASSWORD", "indexer-password-0123456789x");
        env.put("AI_READER_PASSWORD", "reader-password-0123456789xx");
        return env;
    }

    @Test
    void le_o_ambiente_com_valores_padrao() {
        var settings = DatabaseSettings.fromEnvironment(env());

        assertThat(settings.port()).isEqualTo(5432);
        assertThat(settings.database()).isEqualTo("ai");
        assertThat(settings.superuser().user()).isEqualTo("postgres");
        assertThat(settings.url()).isEqualTo(
                "jdbc:postgresql://postgres-ai:5432/ai?logServerErrorDetail=false&ApplicationName=ai-migrate");
    }

    @Test
    void falta_de_senha_impede_o_deploy() {
        var env = env();
        env.remove("AI_READER_PASSWORD");

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env))
                .withMessageContaining("AI_READER_PASSWORD");
    }

    @Test
    void senha_curta_impede_o_deploy() {
        var env = env();
        env.put("AI_INDEXER_PASSWORD", "curta");

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env));
    }

    @Test
    void senha_repetida_impede_o_deploy() {
        var env = env();
        env.put("AI_READER_PASSWORD", env.get("AI_INDEXER_PASSWORD"));

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env));
    }

    @Test
    void senha_nunca_aparece_no_toString() {
        var settings = DatabaseSettings.fromEnvironment(env());

        assertThat(settings.toString()).doesNotContain("password-0123456789").contains("password=***");
    }
}
