package br.com.gilvanborges.javai.ai.api.commitmap;

import br.com.gilvanborges.javai.ai.migrations.DatabaseSettings;
import br.com.gilvanborges.javai.ai.migrations.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** O mapa de commits num PostgreSQL de verdade, com o papel {@code ai_reader} e as mesmas migrações da produção. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcCommitMapStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    static DatabaseSettings db;
    static JdbcCommitMapStore store;

    @BeforeAll
    static void banco() throws SQLException {
        db = TestDatabase.prepare(POSTGRES);
        var ds = new DriverManagerDataSource(db.url(), db.reader().user(), db.reader().password());
        store = new JdbcCommitMapStore(JdbcClient.create(ds));
    }

    @BeforeEach
    void limpa() throws SQLException {
        TestDatabase.truncateAll(db);
    }

    private static void insert(java.sql.Connection c, long version, String repo, String sha, int minutes, String subject) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO index.repo_commit (version_id, repo_full_name, sha, committed_at, subject, url) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, version);
            ps.setString(2, repo);
            ps.setString(3, sha);
            ps.setObject(4, Instant.parse("2026-10-01T00:00:00Z").plusSeconds(minutes * 60L).atOffset(java.time.ZoneOffset.UTC));
            ps.setString(5, subject);
            ps.setString(6, "https://github.com/" + repo + "/commit/" + sha);
            ps.executeUpdate();
        }
    }

    private static long version(java.sql.Connection c, boolean activate) throws SQLException {
        long id;
        try (var st = c.createStatement(); var rs = st.executeQuery("INSERT INTO index.version DEFAULT VALUES RETURNING id")) {
            rs.next();
            id = rs.getLong(1);
        }
        if (activate) {
            try (var st = c.createStatement()) {
                st.execute("SELECT index.activate_version(" + id + ")");
            }
        }
        return id;
    }

    @Test
    void devolve_so_a_versao_ativa_e_no_maximo_o_teto_por_repositorio_do_mais_novo_ao_mais_antigo() throws SQLException {
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            long ativa = version(indexer, true);
            for (int i = 0; i < 5; i++) {
                insert(indexer, ativa, "dono/a", String.format("aaaaaa%d", i), i, "a" + i);
            }
            insert(indexer, ativa, "dono/b", "bbbbbbb", 1, "b1");
            long emConstrucao = version(indexer, false);
            insert(indexer, emConstrucao, "dono/c", "ccccccc", 1, "nao pode aparecer");
        }

        var rows = store.recent(3);

        assertThat(rows).extracting(CommitMapStore.Commit::repoFullName).containsOnly("dono/a", "dono/b");
        var a = rows.stream().filter(c -> c.repoFullName().equals("dono/a")).toList();
        assertThat(a).extracting(CommitMapStore.Commit::subject).containsExactly("a4", "a3", "a2");
        assertThat(a.getFirst().at()).isEqualTo(Instant.parse("2026-10-01T00:04:00Z"));
        assertThat(rows).hasSize(4);
    }

    @Test
    void sem_versao_ativa_nao_devolve_nada() {
        assertThat(store.recent(150)).isEmpty();
    }
}
