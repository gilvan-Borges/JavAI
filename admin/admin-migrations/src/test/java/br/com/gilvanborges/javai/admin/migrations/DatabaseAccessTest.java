package br.com.gilvanborges.javai.admin.migrations;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Prova, num PostgreSQL 18 de verdade, as regras de acesso do banco do CRUD (spec 02, "Testes que provam o acesso"). */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseAccessTest {

    private static final String SEM_PERMISSAO = "42501";
    private static final String CHECK_VIOLADO = "23514";
    private static final String UNICO_VIOLADO = "23505";
    private static final String SENHA_INVALIDA = "28P01";

    private static final Credentials INTRUSO = new Credentials("intruso", "intruso-test-password-0123456789");

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    static DatabaseSettings db;

    @BeforeAll
    static void bootstrapEMigracoes() throws SQLException {
        db = TestDatabase.prepare(POSTGRES);
    }

    @BeforeEach
    void limpa() throws SQLException {
        TestDatabase.truncateAll(db);
    }

    @Test
    void teste01_grant_errado_continua_mostrando_zero_linhas() throws SQLException {
        try (var app = as(db.app())) {
            update(app, "INSERT INTO content.profile (full_name, updated_by) VALUES ('Gilvan', 'teste@example.com')");
        }
        try (var superuser = DriverManager.getConnection(db.maintenanceUrl(), db.superuser().user(), db.superuser().password());
             var owner = as(db.owner())) {
            update(superuser, "CREATE ROLE intruso LOGIN PASSWORD '" + INTRUSO.password() + "'");
            update(superuser, "GRANT CONNECT ON DATABASE admin TO intruso");
            update(owner, "GRANT USAGE ON SCHEMA content TO intruso");
            update(owner, "GRANT SELECT ON content.profile TO intruso");
            try (var intruso = as(INTRUSO)) {
                assertThat(count(intruso, "SELECT count(*) FROM content.profile")).isZero();
            } finally {
                update(owner, "REVOKE SELECT ON content.profile FROM intruso");
                update(owner, "REVOKE USAGE ON SCHEMA content FROM intruso");
                update(superuser, "REVOKE CONNECT ON DATABASE admin FROM intruso");
                update(superuser, "DROP ROLE intruso");
            }
        }
    }

    @Test
    void teste02_auditoria_so_aceita_novas_linhas() throws SQLException {
        try (var app = as(db.app())) {
            update(app, """
                    INSERT INTO ops.audit_log (actor, action, entity, entity_id)
                    VALUES ('teste@example.com', 'create', 'experience', '1')""");
            assertSqlState(SEM_PERMISSAO, () -> update(app, "UPDATE ops.audit_log SET actor = 'outro@example.com'"));
            assertSqlState(SEM_PERMISSAO, () -> update(app, "DELETE FROM ops.audit_log"));
        }
    }

    @Test
    void teste03_app_nao_cria_objetos_nem_trunca() throws SQLException {
        try (var app = as(db.app())) {
            assertSqlState(SEM_PERMISSAO, () -> update(app, "TRUNCATE content.profile"));
            assertSqlState(SEM_PERMISSAO, () -> update(app, "CREATE TABLE content.intrusa (id int)"));
            assertSqlState(SEM_PERMISSAO, () -> update(app, "CREATE TABLE public.intrusa (id int)"));
            assertSqlState(SEM_PERMISSAO, () -> update(app, "CREATE SCHEMA intruso"));
        }
    }

    @Test
    void teste04_app_nao_conecta_no_banco_de_manutencao() {
        assertSqlState(SEM_PERMISSAO,
                () -> DriverManager.getConnection(db.maintenanceUrl(), db.app().user(), db.app().password()).close());
    }

    @Test
    void teste05_publicar_exige_os_dois_idiomas() throws SQLException {
        try (var app = as(db.app())) {
            assertSqlState(CHECK_VIOLADO, () -> update(app, """
                    INSERT INTO content.experience (company, role_pt, started_on, description_pt, description_en, status, updated_by)
                    VALUES ('Vibetex', 'Desenvolvedor', '2025-01-01', 'pt', 'en', 'published', 'teste@example.com')"""));
            update(app, """
                    INSERT INTO content.experience (company, role_pt, role_en, started_on, description_pt, description_en, status, updated_by)
                    VALUES ('Vibetex', 'Desenvolvedor', 'Developer', '2025-01-01', 'pt', 'en', 'published', 'teste@example.com')""");
        }
    }

    @Test
    void teste06_fork_ou_repositorio_privado_nao_pode_ser_ligado() throws SQLException {
        try (var app = as(db.app())) {
            assertSqlState(CHECK_VIOLADO, () -> repo(app, "gilvan-Borges/KaioMuniz", true, true, false));
            assertSqlState(CHECK_VIOLADO, () -> repo(app, "gilvan-Borges/privado", true, false, true));
            repo(app, "gilvan-Borges/KaioMuniz", false, true, false);
            assertThat(count(app, "SELECT count(*) FROM content.github_repo")).isEqualTo(1);
        }
    }

    @Test
    void teste07_ativacao_mantem_uma_unica_versao_ativa() throws SQLException {
        try (var app = as(db.app())) {
            long v1 = newVersion(app);
            long v2 = newVersion(app);
            activate(app, v1);
            activate(app, v2);

            assertThat(statusOf(app, v1)).isEqualTo("retired");
            assertThat(statusOf(app, v2)).isEqualTo("active");
            assertSqlState(UNICO_VIOLADO, () -> {
                try (var ps = app.prepareStatement("UPDATE publish.version SET status = 'active' WHERE id = ?")) {
                    ps.setLong(1, v1);
                    ps.executeUpdate();
                }
            });
        }
    }

    @Test
    void teste08_ativacao_guarda_tres_versoes_para_rollback() throws SQLException {
        try (var app = as(db.app())) {
            for (int i = 0; i < 5; i++) {
                activate(app, newVersion(app));
            }
            assertThat(count(app, "SELECT count(*) FROM publish.version WHERE status = 'retired'")).isEqualTo(3);
            assertThat(count(app, "SELECT count(*) FROM publish.version WHERE status = 'active'")).isEqualTo(1);
        }
    }

    @Test
    void teste09_bootstrap_e_idempotente_e_troca_a_senha() throws SQLException {
        var novaSenha = new Credentials(Roles.APP, "app-nova-senha-0123456789-xyz");
        var rotacionado = new DatabaseSettings(db.host(), db.port(), db.database(), db.superuser(), db.owner(), novaSenha);
        try {
            Bootstrap.run(rotacionado);
            assertSqlState(SENHA_INVALIDA, () -> as(db.app()).close());
            try (var app = as(novaSenha)) {
                assertThat(count(app, "SELECT count(*) FROM content.profile")).isZero();
            }
        } finally {
            Bootstrap.run(db);
        }
    }

    @Test
    void teste10_app_recebe_os_limites_de_sessao() throws SQLException {
        try (var app = as(db.app())) {
            assertThat(strings(app, "SHOW statement_timeout")).containsExactly("30s");
            assertThat(strings(app, "SHOW idle_in_transaction_session_timeout")).containsExactly("10s");
            assertThat(strings(app, "SHOW search_path")).containsExactly("pg_catalog");
        }
    }

    @Test
    void versao_inexistente_nao_pode_ser_ativada() throws SQLException {
        try (var app = as(db.app())) {
            assertSqlState("P0001", () -> activate(app, 999_999));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static Connection as(Credentials who) throws SQLException {
        return TestDatabase.connect(db, who);
    }

    private static long newVersion(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("INSERT INTO publish.version DEFAULT VALUES RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void activate(Connection c, long version) throws SQLException {
        try (var ps = c.prepareStatement("SELECT publish.activate_version(?)")) {
            ps.setLong(1, version);
            ps.execute();
        }
    }

    private static String statusOf(Connection c, long version) throws SQLException {
        try (var ps = c.prepareStatement("SELECT status FROM publish.version WHERE id = ?")) {
            ps.setLong(1, version);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void repo(Connection c, String fullName, boolean enabled, boolean fork, boolean isPrivate)
            throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO content.github_repo (full_name, enabled, is_fork, is_private, updated_by)
                VALUES (?, ?, ?, ?, 'teste@example.com')""")) {
            ps.setString(1, fullName);
            ps.setBoolean(2, enabled);
            ps.setBoolean(3, fork);
            ps.setBoolean(4, isPrivate);
            ps.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void assertSqlState(String expected, ThrowingCallable call) {
        assertThatExceptionOfType(SQLException.class).isThrownBy(call)
                .satisfies(e -> assertThat(e.getSQLState()).isEqualTo(expected));
    }
}
