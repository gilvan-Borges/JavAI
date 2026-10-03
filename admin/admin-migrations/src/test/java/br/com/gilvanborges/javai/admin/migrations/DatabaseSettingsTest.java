package br.com.gilvanborges.javai.admin.migrations;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DatabaseSettingsTest {

    private static Map<String, String> env() {
        var env = new HashMap<String, String>();
        env.put("ADMIN_DB_HOST", "postgres-admin");
        env.put("POSTGRES_PASSWORD", "superuser-password-0123456789");
        env.put("ADMIN_OWNER_PASSWORD", "owner-password-0123456789xx");
        env.put("ADMIN_APP_PASSWORD", "app-password-0123456789xxxxx");
        return env;
    }

    @Test
    void le_o_ambiente_com_valores_padrao() {
        var settings = DatabaseSettings.fromEnvironment(env());

        assertThat(settings.port()).isEqualTo(5432);
        assertThat(settings.database()).isEqualTo("admin");
        assertThat(settings.superuser().user()).isEqualTo("postgres");
        assertThat(settings.url()).isEqualTo(
                "jdbc:postgresql://postgres-admin:5432/admin?logServerErrorDetail=false&ApplicationName=admin-migrate");
    }

    @Test
    void falta_de_senha_impede_o_deploy() {
        var env = env();
        env.remove("ADMIN_APP_PASSWORD");

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env))
                .withMessageContaining("ADMIN_APP_PASSWORD");
    }

    @Test
    void senha_curta_impede_o_deploy() {
        var env = env();
        env.put("ADMIN_OWNER_PASSWORD", "curta");

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env));
    }

    @Test
    void senha_repetida_impede_o_deploy() {
        var env = env();
        env.put("ADMIN_APP_PASSWORD", env.get("ADMIN_OWNER_PASSWORD"));

        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseSettings.fromEnvironment(env));
    }

    @Test
    void senha_nunca_aparece_no_toString() {
        var settings = DatabaseSettings.fromEnvironment(env());

        assertThat(settings.toString()).doesNotContain("password-0123456789").contains("password=***");
    }
}
