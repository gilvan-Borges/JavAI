package br.com.gilvanborges.javai.ai.api.index;

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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O leitor contra um PostgreSQL de verdade, criado pelo mesmo bootstrap e pelas mesmas migrações da produção: lê com
 * o papel {@code ai_reader}, então o RLS que esconde as versões que não são a ativa vale aqui também.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcIndexRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    static DatabaseSettings db;
    static JdbcIndexRepository repository;

    @BeforeAll
    static void banco() throws SQLException {
        db = TestDatabase.prepare(POSTGRES);
        var ds = new DriverManagerDataSource(db.url(), db.reader().user(), db.reader().password());
        repository = new JdbcIndexRepository(JdbcClient.create(ds));
    }

    @BeforeEach
    void limpa() throws SQLException {
        TestDatabase.truncateAll(db);
    }

    @Test
    void sem_versao_ativa_nao_ha_indice() {
        assertThat(repository.activeVersionId()).isEmpty();
        assertThat(repository.loadActive()).isEmpty();
    }

    @Test
    void carrega_trechos_vetores_e_evidencias_da_versao_ativa() throws Exception {
        long version = novaVersao("multilingual-e5-small-int8");
        trecho(version, "project", "project:api-suporte", "en", "apiSuporte", "Support tickets.", vetor(0.1f));
        trecho(version, "skill", "skill:RabbitMQ", "en", "RabbitMQ", "Topic exchanges.", vetor(0.2f));
        evidencia(version, "RabbitMQ", "FightConnect2025/apiSuporte", "src/Publisher.java", 42);
        ativa(version);

        assertThat(repository.activeVersionId()).contains(version);
        ActiveIndex index = repository.loadActive().orElseThrow();

        assertThat(index.versionId()).isEqualTo(version);
        assertThat(index.embeddingModel()).isEqualTo("multilingual-e5-small-int8");
        assertThat(index.chunkCount()).isEqualTo(2);
        assertThat(index.hasVectors()).isTrue();
        assertThat(index.evidence()).hasSize(1);
        assertThat(index.evidence().get(0).line()).isEqualTo(42);
        assertThat(index.withRef("project:api-suporte", "en")).hasSize(1);
    }

    @Test
    void sem_vetores_o_indice_carrega_so_para_bm25() throws Exception {
        long version = novaVersao("multilingual-e5-small-int8");
        trecho(version, "faq", "faq:Qual a stack?", "pt", "FAQ", "Java.", null);
        ativa(version);

        ActiveIndex index = repository.loadActive().orElseThrow();

        assertThat(index.hasVectors()).isFalse();
        assertThat(index.chunkCount()).isEqualTo(1);
    }

    @Test
    void o_rls_esconde_a_versao_em_construcao() throws Exception {
        long active = novaVersao("m");
        trecho(active, "faq", "faq:a", "pt", "A", "ativa.", null);
        ativa(active);
        long building = novaVersao("m");
        trecho(building, "faq", "faq:b", "pt", "B", "em construcao.", null);

        ActiveIndex index = repository.loadActive().orElseThrow();

        assertThat(index.versionId()).isEqualTo(active);
        assertThat(index.chunkCount()).isEqualTo(1);
        assertThat(repository.activeVersionId()).contains(active);
    }

    // ---------------------------------------------------------------- apoio (como o ai_indexer)

    private static long novaVersao(String model) throws SQLException {
        try (Connection c = TestDatabase.connect(db, db.indexer());
             var st = c.prepareStatement("INSERT INTO index.version (embedding_model) VALUES (?) RETURNING id")) {
            st.setString(1, model);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void trecho(long version, String kind, String ref, String lang, String title, String body, float[] vector)
            throws SQLException {
        try (Connection c = TestDatabase.connect(db, db.indexer());
             var st = c.prepareStatement("""
                     INSERT INTO index.chunk (version_id, source_kind, source_ref, lang, title, body, url, embedding, body_hash)
                     VALUES (?, ?, ?, ?, ?, ?, 'https://github.com/gilvan-Borges', ?, ?)""")) {
            st.setLong(1, version);
            st.setString(2, kind);
            st.setString(3, ref);
            st.setString(4, lang);
            st.setString(5, title);
            st.setString(6, body);
            st.setArray(7, vector == null ? null : c.createArrayOf("float4", boxed(vector)));
            st.setString(8, "hash-" + ref);
            st.executeUpdate();
        }
    }

    private static void evidencia(long version, String skill, String repo, String path, int line) throws SQLException {
        try (Connection c = TestDatabase.connect(db, db.indexer());
             var st = c.prepareStatement("""
                     INSERT INTO index.skill_evidence (version_id, skill, repo_full_name, path, line, detector, url)
                     VALUES (?, ?, ?, ?, ?, 'amqp-template', 'https://github.com/FightConnect2025/apiSuporte/blob/abc/x#L1')""")) {
            st.setLong(1, version);
            st.setString(2, skill);
            st.setString(3, repo);
            st.setString(4, path);
            st.setInt(5, line);
            st.executeUpdate();
        }
    }

    private static void ativa(long version) throws SQLException {
        try (Connection c = TestDatabase.connect(db, db.indexer());
             var st = c.prepareStatement("SELECT index.activate_version(?)")) {
            st.setLong(1, version);
            st.execute();
        }
    }

    private static float[] vetor(float value) {
        float[] v = new float[384];
        Arrays.fill(v, value);
        return v;
    }

    private static Float[] boxed(float[] v) {
        Float[] out = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i];
        }
        return out;
    }
}
