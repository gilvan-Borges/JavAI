package br.com.gilvanborges.javai.ai.indexer;

import br.com.gilvanborges.javai.ai.core.embedding.Embedder;
import br.com.gilvanborges.javai.ai.indexer.embedding.EmbedderProvider;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;
import br.com.gilvanborges.javai.ai.indexer.snapshot.SnapshotClient;
import br.com.gilvanborges.javai.ai.indexer.sources.PublicSources;
import br.com.gilvanborges.javai.ai.indexer.sources.SourcesProperties;
import br.com.gilvanborges.javai.ai.indexer.store.IndexStore;
import br.com.gilvanborges.javai.ai.migrations.DatabaseSettings;
import br.com.gilvanborges.javai.ai.migrations.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static br.com.gilvanborges.javai.ai.indexer.IndexerFixtures.fakeEmbedder;
import static br.com.gilvanborges.javai.ai.indexer.IndexerFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O serviço inteiro contra um PostgreSQL de verdade, criado pelo mesmo bootstrap e pelas mesmas migrações da produção.
 * A rede e o modelo são dublês; o banco, o RLS e as transações não.
 */
@Testcontainers(disabledWithoutDocker = true)
class IndexerServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    static DatabaseSettings db;
    static IndexStore store;
    static JsonMapper json = JsonMapper.builder().build();

    FakeSnapshots snapshots;
    FakeRepos repos;
    FakeEmbedders embedders;
    IndexerService service;

    @BeforeAll
    static void banco() throws SQLException {
        db = TestDatabase.prepare(POSTGRES);
        var ds = new DriverManagerDataSource(db.url(), db.indexer().user(), db.indexer().password());
        store = new IndexStore(JdbcClient.create(ds), new JdbcTemplate(ds), json);
    }

    private PublicSources noSources;

    @BeforeEach
    void limpaEMonta() throws SQLException {
        TestDatabase.truncateAll(db);
        snapshots = new FakeSnapshots();
        repos = new FakeRepos();
        embedders = new FakeEmbedders(Optional.of(fakeEmbedder(384)));
        var props = new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/nada", 1,
                "https://api.github.com", "https://github.com/", java.time.Duration.ZERO);
        noSources = new PublicSources(new SourcesProperties(null, null, null, null, false, null, 30, "", "", Duration.ofMinutes(30)), props, json);
        service = new IndexerService(snapshots, repos, embedders, store, json, props, noSources);
    }

    @Test
    void primeira_indexacao_ativa_uma_versao_que_o_reader_consegue_ler() throws Exception {
        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("succeeded");
        try (var reader = TestDatabase.connect(db, db.reader())) {
            assertThat(count(reader, "SELECT count(*) FROM index.version")).isEqualTo(1);
            assertThat(count(reader, "SELECT count(*) FROM index.chunk")).isGreaterThan(5);
            assertThat(count(reader, "SELECT count(*) FROM index.chunk WHERE embedding IS NOT NULL AND cardinality(embedding) = 384"))
                    .isEqualTo(count(reader, "SELECT count(*) FROM index.chunk"));
            assertThat(count(reader, "SELECT count(*) FROM index.skill_evidence")).isGreaterThan(0);
        }
        assertThat(store.activeSnapshotEtag()).isPresent();
    }

    @Test
    void snapshot_que_nao_mudou_nao_reindexa() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);
        snapshots.notModified = true;

        var outcome = service.run(IndexerService.Trigger.SNAPSHOT);

        assertThat(outcome.status()).isEqualTo("skipped");
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            assertThat(count(indexer, "SELECT count(*) FROM index.version")).isEqualTo(1);
            assertThat(strings(indexer, "SELECT status FROM ops.index_job ORDER BY started_at")).containsExactly("succeeded", "skipped");
        }
    }

    @Test
    void a_execucao_agendada_reindexa_mesmo_sem_mudanca_no_snapshot() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);
        snapshots.notModified = true;

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("succeeded");
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            assertThat(strings(indexer, "SELECT status FROM index.version ORDER BY id")).containsExactly("retired", "active");
        }
    }

    @Test
    void commit_novo_num_repositorio_reindexa_mesmo_com_o_snapshot_igual_e_sem_commit_novo_pula() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);
        snapshots.notModified = true;

        assertThat(service.run(IndexerService.Trigger.SNAPSHOT).status()).isEqualTo("skipped");

        repos.head = "b".repeat(40);
        var outcome = service.run(IndexerService.Trigger.SNAPSHOT);

        assertThat(outcome.status()).isEqualTo("succeeded");
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            assertThat(strings(indexer, "SELECT status FROM index.version ORDER BY id")).containsExactly("retired", "active");
        }
        // O commit novo ficou registrado: o ciclo seguinte volta a pular.
        assertThat(service.run(IndexerService.Trigger.SNAPSHOT).status()).isEqualTo("skipped");
    }

    @Test
    void apagar_deixa_a_ia_sem_indice_e_so_o_botao_buscar_dados_reconstroi() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);

        assertThat(service.wipe().status()).isEqualTo("succeeded");

        assertThat(store.activeSnapshotEtag()).isEmpty();
        assertThat(service.run(IndexerService.Trigger.SNAPSHOT).status()).isEqualTo("skipped");
        assertThat(service.run(IndexerService.Trigger.AGENDADO).status()).isEqualTo("skipped");
        assertThat(store.activeSnapshotEtag()).isEmpty();

        assertThat(service.run(IndexerService.Trigger.MANUAL).status()).isEqualTo("succeeded");
        assertThat(store.activeSnapshotEtag()).isPresent();
        assertThat(service.run(IndexerService.Trigger.AGENDADO).status()).isEqualTo("succeeded");
    }

    @Test
    void repositorio_que_recebe_commit_a_toda_hora_respeita_o_intervalo_minimo() throws Exception {
        var slow = new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/nada", 1,
                "https://api.github.com", "https://github.com/", Duration.ofHours(1));
        var guarded = new IndexerService(snapshots, repos, embedders, store, json, slow, noSources);
        guarded.run(IndexerService.Trigger.AGENDADO);
        snapshots.notModified = true;

        repos.head = "b".repeat(40);
        assertThat(guarded.run(IndexerService.Trigger.SNAPSHOT).status()).isEqualTo("succeeded");
        repos.head = "c".repeat(40);
        var held = guarded.run(IndexerService.Trigger.SNAPSHOT);

        assertThat(held.status()).isEqualTo("skipped");
        assertThat(held.report().get("motivo").toString()).contains("intervalo mínimo");
    }

    @Test
    void mudanca_em_um_idioma_le_os_dois_por_inteiro() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);
        snapshots.etagPt = "\"novo\"";
        snapshots.notModifiedLangs = List.of("en");
        snapshots.calls.clear();

        var outcome = service.run(IndexerService.Trigger.SNAPSHOT);

        assertThat(outcome.status()).isEqualTo("succeeded");
        // en respondeu 304 na conferência e foi lido de novo, sem If-None-Match, para a versão ter os dois idiomas completos.
        assertThat(snapshots.calls).contains("en:condicional", "en:completo");
    }

    @Test
    void indexacao_que_falha_nao_derruba_o_indice_ativo() throws Exception {
        service.run(IndexerService.Trigger.AGENDADO);
        embedders.embedder = Optional.of(fakeEmbedder(10)); // vetor de tamanho errado: o CHECK do banco recusa

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("failed");
        try (var reader = TestDatabase.connect(db, db.reader()); var indexer = TestDatabase.connect(db, db.indexer())) {
            // A versão ativa continua a mesma, e o reader continua vendo os trechos dela.
            assertThat(count(reader, "SELECT count(*) FROM index.version")).isEqualTo(1);
            assertThat(count(reader, "SELECT count(*) FROM index.chunk")).isGreaterThan(5);
            assertThat(strings(indexer, "SELECT status FROM index.version ORDER BY id")).containsExactly("active", "failed");
            assertThat(strings(indexer, "SELECT status FROM ops.index_job ORDER BY started_at")).containsExactly("succeeded", "failed");
        }
    }

    @Test
    void falha_ao_ler_o_snapshot_nao_cria_versao_e_registra_o_motivo() throws Exception {
        snapshots.failWith = new IOException("snapshot fora do ar");

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.report().get("motivo").toString()).contains("IOException").contains("fora do ar");
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            assertThat(count(indexer, "SELECT count(*) FROM index.version")).isZero();
            assertThat(strings(indexer, "SELECT status FROM ops.index_job")).containsExactly("failed");
        }
    }

    @Test
    void sem_modelo_a_versao_entra_so_com_bm25() throws Exception {
        embedders.embedder = Optional.empty();

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("succeeded");
        try (var reader = TestDatabase.connect(db, db.reader())) {
            assertThat(count(reader, "SELECT count(*) FROM index.chunk WHERE embedding IS NOT NULL")).isZero();
            assertThat(count(reader, "SELECT count(*) FROM index.chunk")).isGreaterThan(5);
        }
    }

    @Test
    void repositorio_pulado_nao_impede_a_indexacao_e_fica_no_relatorio() throws Exception {
        repos.skip = true;

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("succeeded");
        assertThat(outcome.report().get("repositoriosPulados").toString()).contains("fork");
    }

    @Test
    void o_mapa_de_commits_vai_na_mesma_versao_dos_trechos_e_o_reader_so_ve_a_ativa() throws Exception {
        var first = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(first.status()).isEqualTo("succeeded");
        assertThat(first.report()).containsEntry("commitsNoMapa", 2);
        try (var reader = TestDatabase.connect(db, db.reader())) {
            assertThat(strings(reader, "SELECT sha || ' ' || subject FROM index.repo_commit ORDER BY committed_at DESC"))
                    .containsExactly("aaaaaaa feat: segundo", "1234567 feat: primeiro");
            assertThat(strings(reader, "SELECT DISTINCT repo_full_name FROM index.repo_commit")).containsExactly("FightConnect2025/apiSuporte");
        }
        // A reindexação troca a versão: as linhas da antiga ficam invisíveis para o reader (e não se somam às novas).
        assertThat(service.run(IndexerService.Trigger.AGENDADO).status()).isEqualTo("succeeded");
        try (var reader = TestDatabase.connect(db, db.reader())) {
            assertThat(count(reader, "SELECT count(*) FROM index.repo_commit")).isEqualTo(2);
        }
    }

    @Test
    void repositorio_pulado_nao_deixa_commit_no_mapa() throws Exception {
        repos.skip = true;

        var outcome = service.run(IndexerService.Trigger.AGENDADO);

        assertThat(outcome.status()).isEqualTo("succeeded");
        assertThat(outcome.report()).containsEntry("commitsNoMapa", 0);
        try (var indexer = TestDatabase.connect(db, db.indexer())) {
            assertThat(count(indexer, "SELECT count(*) FROM index.repo_commit")).isZero();
        }
    }

    // --- dublês ---------------------------------------------------------------------------------------------

    static class FakeSnapshots extends SnapshotClient {
        boolean notModified;
        List<String> notModifiedLangs = List.of();
        String etagPt = "\"pt-1\"";
        IOException failWith;
        final List<String> calls = new ArrayList<>();

        FakeSnapshots() {
            super(new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/nada", 1,
                    "https://api.github.com", "https://github.com/", java.time.Duration.ZERO));
        }

        @Override
        public Fetch fetch(String lang, String previousEtag) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            calls.add(lang + ":" + (previousEtag == null ? "completo" : "condicional"));
            String etag = lang.equals("pt") ? etagPt : "\"en-1\"";
            boolean same = previousEtag != null && (notModified || notModifiedLangs.contains(lang)) || previousEtag != null && previousEtag.equals(etag);
            return same ? new Fetch(previousEtag, Optional.empty()) : new Fetch(etag, Optional.of(snapshot(lang)));
        }
    }

    static class FakeRepos implements RepoSource {
        boolean skip;
        String head = "a".repeat(40);

        @Override
        public Optional<String> head(String fullName) {
            return Optional.ofNullable(head);
        }

        @Override
        public RepoRead read(String fullName) {
            if (skip) {
                return RepoRead.skipped(fullName, "repositório é fork");
            }
            var plain = IndexerFixtures.repoAt(fullName, head,
                    "README.md", "# apiSuporte\nMicrosserviço de tickets com RabbitMQ.",
                    "pom.xml", "<dependency><artifactId>spring-boot-starter-amqp</artifactId></dependency>");
            return new RepoRead(plain.fullName(), plain.commitSha(), plain.files(), null, plain.notes(), List.of(
                    new CommitRef(head.substring(0, 7), java.time.Instant.parse("2026-10-03T14:21:00Z"), "feat: segundo"),
                    new CommitRef("1234567", java.time.Instant.parse("2026-10-01T09:00:00Z"), "feat: primeiro")));
        }
    }

    static class FakeEmbedders extends EmbedderProvider {
        Optional<Embedder> embedder;

        FakeEmbedders(Optional<Embedder> embedder) {
            super(new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/nada", 1,
                    "https://api.github.com", "https://github.com/", java.time.Duration.ZERO));
            this.embedder = embedder;
        }

        @Override
        public synchronized Optional<Embedder> get() {
            return embedder;
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------

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
}
