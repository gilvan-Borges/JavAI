package br.com.gilvanborges.javai.ai.api.commitmap;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CommitMapServiceTest {

    private static final Instant ATIVADO = Instant.parse("2026-10-04T12:00:00Z");

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T15:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeStore implements CommitMapStore {
        final List<Commit> rows = new ArrayList<>();
        int calls;
        int lastLimit;

        @Override
        public List<Commit> recent(int perRepo) {
            calls++;
            lastLimit = perRepo;
            return List.copyOf(rows);
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    /** Um repositório de índice com versão e data de ativação à escolha, para provar a chave do cache e o updatedAt. */
    private static final class Versioned implements IndexRepository {
        ActiveIndex index;
        Instant activatedAt;

        @Override
        public Optional<Long> activeVersionId() {
            return Optional.ofNullable(index).map(ActiveIndex::versionId);
        }

        @Override
        public Optional<VersionMeta> activeMeta() {
            return Optional.ofNullable(index).map(i -> new VersionMeta(i.versionId(), activatedAt, null, null, "{}"));
        }

        @Override
        public Optional<ActiveIndex> loadActive() {
            return Optional.ofNullable(index);
        }
    }

    private static CommitMapStore.Commit commit(String repo, String sha, String at, String subject) {
        return new CommitMapStore.Commit(repo, sha, Instant.parse(at), subject);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> repos(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("repos");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> commits(Map<String, Object> repo) {
        return (List<Map<String, Object>>) repo.get("commits");
    }

    @Test
    void ordena_repos_por_quantidade_de_commits_e_depois_pelo_nome_e_commits_do_mais_novo_ao_mais_antigo() {
        var store = new FakeStore();
        store.rows.add(commit("dono/zeta", "1111111", "2026-10-01T10:00:00Z", "um"));
        store.rows.add(commit("dono/alfa", "2222222", "2026-09-01T10:00:00Z", "velho"));
        store.rows.add(commit("dono/alfa", "3333333", "2026-10-03T14:21:00Z", "novo"));
        store.rows.add(commit("dono/beta", "4444444", "2026-10-02T10:00:00Z", "b1"));
        store.rows.add(commit("dono/maior", "5555555", "2026-10-02T11:00:00Z", "m1"));
        store.rows.add(commit("dono/maior", "6666666", "2026-10-02T12:00:00Z", "m2"));
        store.rows.add(commit("dono/maior", "7777777", "2026-10-02T13:00:00Z", "m3"));

        var body = CommitMapService.build(store.recent(150), ATIVADO);

        assertThat(repos(body)).extracting(r -> r.get("name")).containsExactly("maior", "alfa", "beta", "zeta");
        assertThat(commits(repos(body).get(1))).extracting(c -> c.get("subject")).containsExactly("novo", "velho");
        assertThat(commits(repos(body).get(0))).extracting(c -> c.get("sha")).containsExactly("7777777", "6666666", "5555555");
    }

    @Test
    void totais_url_e_nome_seguem_o_contrato() {
        var rows = List.of(
                commit("gilvan-Borges/controlei", "9d00098", "2026-10-03T14:21:00Z", "fix(brain): um ponto por repositório"),
                commit("gilvan-Borges/controlei", "de6c0b0", "2026-10-02T10:00:00Z", "feat(brain): mapa"),
                commit("gilvan-Borges/noxeval", "1f7071e", "2026-10-01T10:00:00Z", "feat(infra): variáveis"));

        var body = CommitMapService.build(rows, ATIVADO);

        assertThat(body.keySet()).containsExactly("updatedAt", "totals", "repos");
        assertThat(body.get("updatedAt")).isEqualTo("2026-10-04T12:00:00Z");
        assertThat(body.get("totals")).isEqualTo(Map.of("repos", 2, "commits", 3));
        var first = repos(body).getFirst();
        assertThat(first.keySet()).containsExactly("name", "fullName", "url", "commits");
        assertThat(first).containsEntry("name", "controlei").containsEntry("fullName", "gilvan-Borges/controlei")
                .containsEntry("url", "https://github.com/gilvan-Borges/controlei");
        assertThat(commits(first).getFirst().keySet()).containsExactly("sha", "at", "subject");
        assertThat(commits(first).getFirst()).containsEntry("sha", "9d00098").containsEntry("at", "2026-10-03T14:21:00Z");
    }

    @Test
    void corta_em_150_por_repositorio_e_o_hash_em_7_caracteres() {
        var rows = new ArrayList<CommitMapStore.Commit>();
        for (int i = 0; i < 200; i++) {
            rows.add(new CommitMapStore.Commit("dono/longo", "abcdef0123456789", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i), "c" + i));
        }

        var body = CommitMapService.build(rows, ATIVADO);

        assertThat(commits(repos(body).getFirst())).hasSize(150);
        assertThat(commits(repos(body).getFirst()).getFirst()).containsEntry("subject", "c199").containsEntry("sha", "abcdef0");
        assertThat(body.get("totals")).isEqualTo(Map.of("repos", 1, "commits", 150));
    }

    @Test
    void sem_indice_ativo_nao_ha_mapa_e_o_banco_nem_e_consultado() {
        var store = new FakeStore();
        var service = new CommitMapService(ApiFixtures.holder(null), provider(store), new MutableClock());

        assertThat(service.snapshot()).isEmpty();
        assertThat(store.calls).isZero();
    }

    @Test
    void o_corpo_fica_em_cache_por_dez_minutos_e_por_versao_do_indice() {
        var repository = new Versioned();
        repository.index = ApiFixtures.index();
        repository.activatedAt = ATIVADO;
        var holder = new IndexHolder(repository);
        holder.refresh();
        var store = new FakeStore();
        store.rows.add(commit("dono/a", "1111111", "2026-10-01T10:00:00Z", "um"));
        var clock = new MutableClock();
        var service = new CommitMapService(holder, provider(store), clock);

        var first = service.snapshot().orElseThrow();
        assertThat(first.get("updatedAt")).isEqualTo("2026-10-04T12:00:00Z");
        assertThat(store.lastLimit).isEqualTo(150);

        store.rows.add(commit("dono/a", "2222222", "2026-10-02T10:00:00Z", "dois"));
        clock.now = clock.now.plusSeconds(9 * 60);
        assertThat(service.snapshot().orElseThrow()).isSameAs(first);
        assertThat(store.calls).isEqualTo(1);

        clock.now = clock.now.plusSeconds(2 * 60);
        var second = service.snapshot().orElseThrow();
        assertThat(second).isNotSameAs(first);
        assertThat(store.calls).isEqualTo(2);
        assertThat(second.get("totals")).isEqualTo(Map.of("repos", 1, "commits", 2));

        // Versão nova do índice invalida o cache na hora, sem esperar os dez minutos.
        store.rows.add(commit("dono/b", "3333333", "2026-10-03T10:00:00Z", "tres"));
        repository.index = new ActiveIndex(2, "multilingual-e5-small-int8", ApiFixtures.index().chunks(), null, List.of(), new ScalarVectorOps());
        repository.activatedAt = Instant.parse("2026-10-04T15:10:00Z");
        holder.refresh();
        var third = service.snapshot().orElseThrow();
        assertThat(store.calls).isEqualTo(3);
        assertThat(third.get("updatedAt")).isEqualTo("2026-10-04T15:10:00Z");
        assertThat(third.get("totals")).isEqualTo(Map.of("repos", 2, "commits", 3));
    }

    @Test
    void sem_data_de_ativacao_conhecida_usa_o_instante_atual() {
        var service = new CommitMapService(ApiFixtures.holder(ApiFixtures.index()), provider(new FakeStore()), new MutableClock());

        var body = service.snapshot().orElseThrow();

        assertThat(body.get("updatedAt")).isEqualTo("2026-10-04T15:00:00Z");
        assertThat(body.get("totals")).isEqualTo(Map.of("repos", 0, "commits", 0));
        assertThat(repos(body)).isEmpty();
    }
}
