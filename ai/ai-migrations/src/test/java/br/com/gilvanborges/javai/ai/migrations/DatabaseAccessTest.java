package br.com.gilvanborges.javai.ai.migrations;

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

/** Prova, num PostgreSQL 18 de verdade, as regras de acesso do banco da IA (spec 02, "Testes (Fase 2)"). */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseAccessTest {

    private static final String SEM_PERMISSAO = "42501";
    private static final String CHECK_VIOLADO = "23514";
    private static final String UNICO_VIOLADO = "23505";
    private static final String CONSULTA_CANCELADA = "57014";
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
    void teste01_reader_le_so_os_trechos_da_versao_ativa() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long ativa = newVersion(indexer);
            chunk(indexer, ativa, "da ativa");
            long emConstrucao = newVersion(indexer);
            chunk(indexer, emConstrucao, "em construcao");
            activate(indexer, ativa);
        }
        try (var reader = as(db.reader())) {
            assertThat(strings(reader, "SELECT title FROM index.chunk")).containsExactly("da ativa");
            assertThat(count(reader, "SELECT count(*) FROM index.version")).isEqualTo(1);
        }
    }

    @Test
    void teste02_reader_nao_escreve_no_indice() throws SQLException {
        long versao;
        try (var indexer = as(db.indexer())) {
            versao = newVersion(indexer);
            chunk(indexer, versao, "publico");
            activate(indexer, versao);
        }
        try (var reader = as(db.reader())) {
            assertSqlState(SEM_PERMISSAO, () -> chunk(reader, versao, "injetado"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "UPDATE index.chunk SET title = 'alterado'"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "DELETE FROM index.chunk"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "INSERT INTO index.version DEFAULT VALUES"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "UPDATE index.version SET status = 'failed'"));
        }
    }

    @Test
    void teste03_reader_nao_le_as_execucoes_do_indexador() throws SQLException {
        try (var reader = as(db.reader())) {
            assertSqlState(SEM_PERMISSAO, () -> count(reader, "SELECT count(*) FROM ops.index_job"));
        }
    }

    @Test
    void teste04_consulta_lenta_do_reader_e_cancelada() throws SQLException {
        try (var reader = as(db.reader())) {
            assertSqlState(CONSULTA_CANCELADA, () -> count(reader, "SELECT count(*) FROM pg_sleep(3)"));
        }
    }

    @Test
    void teste05_grant_errado_continua_mostrando_zero_linhas() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long versao = newVersion(indexer);
            chunk(indexer, versao, "x");
            activate(indexer, versao);
        }
        try (var superuser = DriverManager.getConnection(db.maintenanceUrl(), db.superuser().user(), db.superuser().password());
             var owner = as(db.owner())) {
            update(superuser, "CREATE ROLE intruso LOGIN PASSWORD '" + INTRUSO.password() + "'");
            update(superuser, "GRANT CONNECT ON DATABASE ai TO intruso");
            update(owner, "GRANT USAGE ON SCHEMA index TO intruso");
            update(owner, "GRANT SELECT ON index.chunk TO intruso");
            try (var intruso = as(INTRUSO)) {
                assertThat(count(intruso, "SELECT count(*) FROM index.chunk")).isZero();
            } finally {
                update(owner, "REVOKE SELECT ON index.chunk FROM intruso");
                update(owner, "REVOKE USAGE ON SCHEMA index FROM intruso");
                update(superuser, "REVOKE CONNECT ON DATABASE ai FROM intruso");
                update(superuser, "DROP ROLE intruso");
            }
        }
    }

    @Test
    void teste06_ativacao_mantem_uma_unica_versao_ativa() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long v1 = newVersion(indexer);
            long v2 = newVersion(indexer);
            activate(indexer, v1);
            activate(indexer, v2);

            assertThat(statusOf(indexer, v1)).isEqualTo("retired");
            assertThat(statusOf(indexer, v2)).isEqualTo("active");
            assertSqlState(UNICO_VIOLADO, () -> update(indexer, "UPDATE index.version SET status = 'active' WHERE id = " + v1));
        }
    }

    @Test
    void teste07_ativacao_guarda_tres_versoes_para_rollback() throws SQLException {
        try (var indexer = as(db.indexer())) {
            for (int i = 0; i < 5; i++) {
                activate(indexer, newVersion(indexer));
            }
            assertThat(count(indexer, "SELECT count(*) FROM index.version WHERE status = 'retired'")).isEqualTo(3);
            assertThat(count(indexer, "SELECT count(*) FROM index.version WHERE status = 'active'")).isEqualTo(1);
        }
    }

    @Test
    void teste08_reader_nao_ativa_versao() throws SQLException {
        long versao;
        try (var indexer = as(db.indexer())) {
            versao = newVersion(indexer);
        }
        try (var reader = as(db.reader())) {
            assertSqlState(SEM_PERMISSAO, () -> activate(reader, versao));
        }
    }

    @Test
    void teste09_contadores_sao_so_do_reader() throws SQLException {
        try (var reader = as(db.reader()); var indexer = as(db.indexer())) {
            update(reader, "INSERT INTO ops.usage_daily (day, model, requests) VALUES (current_date, 'm', 1)");
            assertThat(count(reader, "SELECT requests FROM ops.usage_daily")).isEqualTo(1);
            assertSqlState(SEM_PERMISSAO, () -> count(indexer, "SELECT count(*) FROM ops.usage_daily"));
        }
    }

    @Test
    void teste10_embedding_precisa_ter_384_dimensoes_ou_ser_nulo() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long versao = newVersion(indexer);
            update(indexer, insertChunk(versao, "sem vetor", "NULL"));
            update(indexer, insertChunk(versao, "com vetor", "array_fill(0.1::real, ARRAY[384])"));
            assertSqlState(CHECK_VIOLADO, () -> update(indexer, insertChunk(versao, "vetor curto", "array_fill(0.1::real, ARRAY[10])")));
        }
    }

    @Test
    void teste11_url_precisa_ser_https_e_o_idioma_pt_ou_en() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long versao = newVersion(indexer);
            assertSqlState(CHECK_VIOLADO, () -> update(indexer,
                    insertChunk(versao, "x", "NULL").replace("https://gilvanborges.com.br", "http://gilvanborges.com.br")));
            assertSqlState(CHECK_VIOLADO, () -> update(indexer, insertChunk(versao, "x", "NULL").replace("'pt'", "'es'")));
        }
    }

    @Test
    void teste12_papeis_de_runtime_nao_criam_objetos_nem_truncam() throws SQLException {
        try (var reader = as(db.reader()); var indexer = as(db.indexer())) {
            assertSqlState(SEM_PERMISSAO, () -> update(indexer, "TRUNCATE index.chunk"));
            assertSqlState(SEM_PERMISSAO, () -> update(indexer, "CREATE TABLE index.intrusa (id int)"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "CREATE TABLE public.intrusa (id int)"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "CREATE SCHEMA intruso"));
        }
    }

    @Test
    void teste13_apps_nao_conectam_no_banco_de_manutencao() {
        assertSqlState(SEM_PERMISSAO,
                () -> DriverManager.getConnection(db.maintenanceUrl(), db.reader().user(), db.reader().password()).close());
        assertSqlState(SEM_PERMISSAO,
                () -> DriverManager.getConnection(db.maintenanceUrl(), db.indexer().user(), db.indexer().password()).close());
    }

    @Test
    void teste14_papeis_recebem_os_limites_de_sessao() throws SQLException {
        try (var reader = as(db.reader()); var indexer = as(db.indexer())) {
            assertThat(strings(reader, "SHOW statement_timeout")).containsExactly("2s");
            assertThat(strings(indexer, "SHOW statement_timeout")).containsExactly("1min");
            assertThat(strings(reader, "SHOW search_path")).containsExactly("pg_catalog");
        }
    }

    @Test
    void teste15_bootstrap_e_idempotente_e_troca_a_senha() throws SQLException {
        var novaSenha = new Credentials(Roles.READER, "reader-nova-senha-0123456789-xyz");
        var rotacionado = new DatabaseSettings(db.host(), db.port(), db.database(),
                db.superuser(), db.owner(), db.indexer(), novaSenha);
        try {
            Bootstrap.run(rotacionado);
            assertSqlState(SENHA_INVALIDA, () -> as(db.reader()).close());
            try (var reader = as(novaSenha)) {
                assertThat(count(reader, "SELECT count(*) FROM index.version")).isZero();
            }
        } finally {
            Bootstrap.run(db);
        }
    }

    @Test
    void teste16_ativacao_avisa_o_ai_api_com_notify() throws SQLException {
        try (var listener = as(db.reader()); var indexer = as(db.indexer())) {
            update(listener, "LISTEN ai_index");
            long versao = newVersion(indexer);
            activate(indexer, versao);

            var notifications = listener.unwrap(org.postgresql.PGConnection.class).getNotifications(2000);
            assertThat(notifications).hasSize(1);
            assertThat(notifications[0].getParameter()).isEqualTo(String.valueOf(versao));
        }
    }

    @Test
    void versao_inexistente_nao_pode_ser_ativada() throws SQLException {
        try (var indexer = as(db.indexer())) {
            assertSqlState("P0001", () -> activate(indexer, 999_999));
        }
    }

    @Test
    void teste09_fontes_publicas_tem_tipo_proprio_e_tipo_desconhecido_e_recusado() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long versao = newVersion(indexer);
            for (String tipo : new String[]{"site", "document", "github_profile"}) {
                update(indexer, "INSERT INTO index.chunk (version_id, source_kind, source_ref, lang, title, body, url, body_hash) VALUES ("
                        + versao + ", '" + tipo + "', 'ref', 'pt', 't', 'corpo', 'https://gilvanborges.com.br', 'h')");
            }
            assertThat(count(indexer, "SELECT count(*) FROM index.chunk WHERE source_kind IN ('site', 'document', 'github_profile')")).isEqualTo(3);
            assertSqlState("23514", () -> update(indexer, "INSERT INTO index.chunk (version_id, source_kind, source_ref, lang, title, body, url, body_hash) VALUES ("
                    + versao + ", 'inventado', 'ref', 'pt', 't', 'corpo', 'https://gilvanborges.com.br', 'h')"));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static Connection as(Credentials who) throws SQLException {
        return TestDatabase.connect(db, who);
    }

    private static long newVersion(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("INSERT INTO index.version DEFAULT VALUES RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void activate(Connection c, long version) throws SQLException {
        try (var ps = c.prepareStatement("SELECT index.activate_version(?)")) {
            ps.setLong(1, version);
            ps.execute();
        }
    }

    private static String statusOf(Connection c, long version) throws SQLException {
        return strings(c, "SELECT status FROM index.version WHERE id = " + version).getFirst();
    }

    private static void chunk(Connection c, long version, String title) throws SQLException {
        update(c, insertChunk(version, title, "NULL"));
    }

    private static String insertChunk(long version, String title, String embedding) {
        return "INSERT INTO index.chunk (version_id, source_kind, source_ref, lang, title, body, url, embedding, body_hash) "
                + "VALUES (" + version + ", 'project', 'ref', 'pt', '" + title + "', 'corpo', 'https://gilvanborges.com.br', "
                + embedding + ", 'hash')";
    }

    @Test
    void teste20_cerebro_so_o_reader_grava_e_a_chave_e_a_origem_do_trecho() throws SQLException {
        try (var reader = as(db.reader()); var indexer = as(db.indexer())) {
            update(reader, "INSERT INTO ops.knowledge_heat (day, source_ref, hits) VALUES (current_date, 'repo_readme:dono/x:README.md:Arquitetura', 2)");
            update(reader, "UPDATE ops.knowledge_heat SET hits = hits + 1");
            assertThat(count(reader, "SELECT hits FROM ops.knowledge_heat")).isEqualTo(3);
            update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b, hits) VALUES (current_date, 'a', 'b', 1)");
            assertThat(count(reader, "SELECT count(*) FROM ops.knowledge_pair")).isEqualTo(1);
            // O indexador não enxerga nem escreve nos contadores.
            assertSqlState(SEM_PERMISSAO, () -> count(indexer, "SELECT count(*) FROM ops.knowledge_heat"));
            assertSqlState(SEM_PERMISSAO, () -> update(indexer, "INSERT INTO ops.knowledge_pair (day, source_a, source_b) VALUES (current_date, 'a', 'c')"));
            // Ninguém apaga o histórico (nem o reader): só soma.
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "DELETE FROM ops.knowledge_heat"));
        }
    }

    @Test
    void teste21_cerebro_par_tem_uma_linha_por_combinacao_e_origem_limitada() throws SQLException {
        try (var reader = as(db.reader())) {
            update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b, hits) VALUES (current_date, 'a', 'b', 1)");
            // (b, a) seria a mesma combinação escrita ao contrário: o CHECK source_a < source_b obriga uma só forma.
            assertSqlState(CHECK_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b) VALUES (current_date, 'b', 'a')"));
            assertSqlState(CHECK_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b) VALUES (current_date, 'a', 'a')"));
            assertSqlState(UNICO_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b) VALUES (current_date, 'a', 'b')"));
            // A ordem é a do Java (por código de caractere, maiúscula antes de minúscula), qualquer que seja o idioma do banco.
            update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b, hits) VALUES (current_date, 'Zeta', 'alfa', 1)");
            assertSqlState(CHECK_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_pair (day, source_a, source_b) VALUES (current_date, 'alfa', 'Zeta')"));
            // Texto longo (uma pergunta colada no lugar da origem) não cabe: o limite é de 300 caracteres.
            assertSqlState(CHECK_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_heat (day, source_ref) VALUES (current_date, repeat('x', 301))"));
            assertSqlState(CHECK_VIOLADO, () -> update(reader, "INSERT INTO ops.knowledge_heat (day, source_ref, hits) VALUES (current_date, 'ok', -1)"));
        }
    }

    @Test
    void teste22_mapa_de_commits_reader_le_so_a_versao_ativa_e_o_indexador_ve_tudo() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long ativa = newVersion(indexer);
            commit(indexer, ativa, "dono/ativo", "aaaaaaa", "da ativa");
            long emConstrucao = newVersion(indexer);
            commit(indexer, emConstrucao, "dono/novo", "bbbbbbb", "em construcao");
            activate(indexer, ativa);
            // O indexador enxerga as duas versões (a em construção inclusive).
            assertThat(count(indexer, "SELECT count(*) FROM index.repo_commit")).isEqualTo(2);
        }
        try (var reader = as(db.reader())) {
            assertThat(strings(reader, "SELECT subject FROM index.repo_commit")).containsExactly("da ativa");
        }
    }

    @Test
    void teste23_mapa_de_commits_reader_nao_escreve_e_o_indexador_nao_altera() throws SQLException {
        long versao;
        try (var indexer = as(db.indexer())) {
            versao = newVersion(indexer);
            commit(indexer, versao, "dono/ativo", "aaaaaaa", "publico");
            activate(indexer, versao);
            // O indexador insere e apaga, mas não altera uma linha já gravada.
            assertSqlState(SEM_PERMISSAO, () -> update(indexer, "UPDATE index.repo_commit SET subject = 'x'"));
            assertSqlState(SEM_PERMISSAO, () -> update(indexer, "TRUNCATE index.repo_commit"));
        }
        try (var reader = as(db.reader())) {
            assertSqlState(SEM_PERMISSAO, () -> commit(reader, versao, "dono/ativo", "ccccccc", "injetado"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "UPDATE index.repo_commit SET subject = 'alterado'"));
            assertSqlState(SEM_PERMISSAO, () -> update(reader, "DELETE FROM index.repo_commit"));
        }
    }

    @Test
    void teste24_mapa_de_commits_valida_hash_nome_do_repositorio_url_e_tamanho_e_cai_com_a_versao() throws SQLException {
        try (var indexer = as(db.indexer())) {
            long versao = newVersion(indexer);
            assertSqlState(CHECK_VIOLADO, () -> commit(indexer, versao, "dono/ativo", "XYZ", "hash invalido"));
            assertSqlState(CHECK_VIOLADO, () -> commit(indexer, versao, "dono/ativo", "abc12", "hash curto"));
            assertSqlState(CHECK_VIOLADO, () -> commit(indexer, versao, "dono sem barra", "aaaaaaa", "repositorio invalido"));
            assertSqlState(CHECK_VIOLADO, () -> commit(indexer, versao, "dono/ativo", "aaaaaaa", "x".repeat(201)));
            assertSqlState(CHECK_VIOLADO, () -> update(indexer, commitSql(versao, "dono/ativo", "aaaaaaa", "ok")
                    .replace("https://github.com", "http://github.com")));
            commit(indexer, versao, "dono/ativo", "a".repeat(40), "x".repeat(200));
            assertThat(count(indexer, "SELECT count(*) FROM index.repo_commit")).isEqualTo(1);
            // Os commits caem junto com a versão (ON DELETE CASCADE): o histórico não sobrevive ao índice.
            update(indexer, "DELETE FROM index.version WHERE id = " + versao);
            assertThat(count(indexer, "SELECT count(*) FROM index.repo_commit")).isZero();
        }
    }

    private static void commit(Connection c, long version, String repo, String sha, String subject) throws SQLException {
        update(c, commitSql(version, repo, sha, subject));
    }

    private static String commitSql(long version, String repo, String sha, String subject) {
        return "INSERT INTO index.repo_commit (version_id, repo_full_name, sha, committed_at, subject, url) VALUES ("
                + version + ", '" + repo + "', '" + sha + "', now(), '" + subject + "', 'https://github.com/" + repo + "/commit/" + sha + "')";
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
