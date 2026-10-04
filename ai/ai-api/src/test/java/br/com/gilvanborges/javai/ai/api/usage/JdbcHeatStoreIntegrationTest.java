package br.com.gilvanborges.javai.ai.api.usage;

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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Os contadores do cérebro num PostgreSQL de verdade, com o papel {@code ai_reader} e as mesmas migrações da produção. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcHeatStoreIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 3);

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    static DatabaseSettings db;
    static JdbcHeatStore store;

    @BeforeAll
    static void banco() throws SQLException {
        db = TestDatabase.prepare(POSTGRES);
        var ds = new DriverManagerDataSource(db.url(), db.reader().user(), db.reader().password());
        store = new JdbcHeatStore(JdbcClient.create(ds));
    }

    @BeforeEach
    void limpa() throws SQLException {
        TestDatabase.truncateAll(db);
    }

    @Test
    void soma_cada_origem_e_cada_par_uma_vez_por_resposta() {
        store.record(D1, List.of("repo_readme:dono/a:README.md:Intro", "project:b", "profile"));
        store.record(D1, List.of("project:b", "profile"));

        var heat = store.between(D1, D1);

        assertThat(heat.nodes()).containsEntry("project:b", 2).containsEntry("profile", 2)
                .containsEntry("repo_readme:dono/a:README.md:Intro", 1);
        assertThat(heat.pairs()).containsEntry(HeatStore.Pair.of("profile", "project:b"), 2)
                .containsEntry(HeatStore.Pair.of("project:b", "repo_readme:dono/a:README.md:Intro"), 1)
                .containsEntry(HeatStore.Pair.of("profile", "repo_readme:dono/a:README.md:Intro"), 1);
    }

    @Test
    void repetidos_na_mesma_resposta_contam_uma_vez() {
        store.record(D1, List.of("project:b", "project:b", "profile"));

        var heat = store.between(D1, D1);

        assertThat(heat.nodes()).containsEntry("project:b", 1);
        assertThat(heat.pairs()).hasSize(1);
    }

    @Test
    void soma_o_intervalo_e_ignora_dias_de_fora() {
        store.record(D1, List.of("a", "b"));
        store.record(D2, List.of("a", "b"));
        store.record(D3, List.of("a", "b"));

        assertThat(store.between(D1, D2).nodes()).containsEntry("a", 2);
        assertThat(store.between(D3, D3).nodes()).containsEntry("a", 1);
        assertThat(store.between(D1, D3).pairs()).containsEntry(HeatStore.Pair.of("a", "b"), 3);
        assertThat(store.between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).nodes()).isEmpty();
    }

    @Test
    void ordem_de_caixa_diferente_do_banco_nao_perde_o_par() {
        // 'Zeta' vem antes de 'alfa' por código de caractere (e depois no idioma do banco): o CHECK não pode recusar.
        store.record(D1, List.of("alfa", "Zeta"));

        assertThat(store.between(D1, D1).pairs()).containsEntry(HeatStore.Pair.of("Zeta", "alfa"), 1);
    }

    @Test
    void ignora_origem_vazia_ou_longa_demais_e_limita_o_tamanho_da_resposta() {
        var refs = new java.util.ArrayList<String>(List.of("", " ", "x".repeat(301)));
        for (int i = 0; i < 20; i++) {
            refs.add(String.format("origem-%02d", i));
        }

        store.record(D1, refs);

        var heat = store.between(D1, D1);
        assertThat(heat.nodes()).hasSize(JdbcHeatStore.MAX_REFS).doesNotContainKey("");
        // 8 origens dão 28 pares (n·(n-1)/2): o teto protege o crescimento quadrático.
        assertThat(heat.pairs()).hasSize(JdbcHeatStore.MAX_REFS * (JdbcHeatStore.MAX_REFS - 1) / 2);
    }
}
