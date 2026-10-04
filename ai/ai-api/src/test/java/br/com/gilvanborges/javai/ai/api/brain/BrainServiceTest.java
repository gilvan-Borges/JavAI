package br.com.gilvanborges.javai.ai.api.brain;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.HeatStore;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrainServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T15:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZONE;
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

    /** Contadores por dia, para provar a janela e o atraso. */
    private static final class DayHeat implements HeatStore {
        final Map<LocalDate, Map<String, Integer>> nodes = new HashMap<>();
        final Map<LocalDate, Map<Pair, Integer>> pairs = new HashMap<>();
        int betweenCalls;
        int records;

        void node(LocalDate day, String ref, int hits) {
            nodes.computeIfAbsent(day, d -> new HashMap<>()).merge(ref, hits, Integer::sum);
        }

        void pair(LocalDate day, String a, String b, int hits) {
            pairs.computeIfAbsent(day, d -> new HashMap<>()).merge(Pair.of(a, b), hits, Integer::sum);
        }

        @Override
        public void record(LocalDate day, Collection<String> sourceRefs) {
            records++;
        }

        @Override
        public Heat between(LocalDate from, LocalDate to) {
            betweenCalls++;
            Map<String, Integer> n = new HashMap<>();
            Map<Pair, Integer> p = new HashMap<>();
            nodes.forEach((d, m) -> {
                if (!d.isBefore(from) && !d.isAfter(to)) {
                    m.forEach((k, v) -> n.merge(k, v, Integer::sum));
                }
            });
            pairs.forEach((d, m) -> {
                if (!d.isBefore(from) && !d.isAfter(to)) {
                    m.forEach((k, v) -> p.merge(k, v, Integer::sum));
                }
            });
            return new Heat(n, p);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final DayHeat heat = new DayHeat();
    private final ActiveIndex active = ApiFixtures.index();
    private final BrainProperties props = new BrainProperties(3, 30, 1, 60, 10);

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

    private BrainService service(ActiveIndex index) {
        var aiProps = new AiApiProperties(List.of(), 60, Duration.ofSeconds(30), "/nao-existe", 1, "https://gilvanborges.com.br");
        UsageStore usage = new ApiFixtures.MemoryStore();
        return new BrainService(ApiFixtures.holder(index), provider(heat), provider(usage), new QueryEmbedder(aiProps), clock, props);
    }

    private String ref(String prefix) {
        return active.chunks().stream().map(c -> c.sourceRef()).filter(r -> r.startsWith(prefix)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    private static Map<String, Object> node(Map<String, Object> body, String id) {
        return list(body, "nodes").stream().filter(n -> n.get("id").equals(id)).findFirst().orElseThrow();
    }

    @Test
    void ponto_abaixo_do_minimo_fica_apagado_e_acima_mostra_o_numero() {
        String forte = ref("project:");
        String fraco = ref("profile");
        heat.node(TODAY.minusDays(2), forte, 3);
        heat.node(TODAY.minusDays(3), fraco, 2); // 2 < 3: pode ser uma visita só

        var body = service(active).snapshot().orElseThrow();

        assertThat(node(body, forte).get("hits")).isEqualTo(3);
        assertThat(node(body, fraco).get("hits")).isNull();
        assertThat(body).containsEntry("minHits", 3).containsEntry("hasVectors", false);
    }

    @Test
    void soma_os_dias_da_janela_e_ignora_o_que_ficou_para_tras() {
        String ref = ref("project:");
        heat.node(TODAY.minusDays(2), ref, 2);
        heat.node(TODAY.minusDays(10), ref, 2);
        heat.node(TODAY.minusDays(31), ref, 50); // fora dos 30 dias

        var body = service(active).snapshot().orElseThrow();

        assertThat(node(body, ref).get("hits")).isEqualTo(4);
    }

    @Test
    void hoje_nunca_aparece_porque_dois_retratos_nao_podem_revelar_uma_pergunta_de_agora() {
        String ref = ref("project:");
        heat.node(TODAY, ref, 100);
        heat.node(TODAY.minusDays(1), ref, 3);

        var body = service(active).snapshot().orElseThrow();

        assertThat(node(body, ref).get("hits")).isEqualTo(3);
        assertThat(body).containsEntry("to", TODAY.minusDays(1).toString());
    }

    @Test
    void ramificacoes_agrupam_por_projeto_e_listam_os_mais_escolhidos() {
        String project = ref("project:");
        String group = active.brainLayout().stream().filter(n -> n.id().equals(project)).findFirst().orElseThrow().group();
        heat.node(TODAY.minusDays(2), project, 5);

        var body = service(active).snapshot().orElseThrow();

        var branch = list(body, "branches").stream().filter(b -> b.get("group").equals(group)).findFirst().orElseThrow();
        assertThat(branch.get("hits")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        var top = (List<Map<String, Object>>) branch.get("top");
        assertThat(top).extracting(t -> t.get("id")).contains(project);
        assertThat(list(body, "groups")).extracting(g -> g.get("key")).contains(group);
    }

    @Test
    void ligacao_so_aparece_com_o_minimo_e_entre_pontos_que_existem() {
        String a = ref("project:");
        String b = ref("profile");
        heat.pair(TODAY.minusDays(2), a, b, 3);
        heat.pair(TODAY.minusDays(2), a, "origem-que-nao-existe-mais", 9);
        heat.pair(TODAY.minusDays(3), b, ref("faq"), 2);

        var body = service(active).snapshot().orElseThrow();

        var edges = list(body, "edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0)).containsEntry("hits", 3);
        assertThat(List.of(edges.get(0).get("a"), edges.get(0).get("b"))).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void o_mapa_montado_e_reaproveitado_pelo_tempo_do_cache_e_remontado_depois() {
        var svc = service(active);

        svc.snapshot();
        svc.snapshot();
        assertThat(heat.betweenCalls).isEqualTo(1);

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        svc.snapshot();
        assertThat(heat.betweenCalls).isEqualTo(2);
    }

    @Test
    void sem_indice_nao_ha_mapa() {
        assertThat(service(null).snapshot()).isEmpty();
        assertThat(service(null).probe("qualquer coisa", "pt")).isEmpty();
    }

    @Test
    void a_sonda_devolve_as_origens_na_ordem_e_nao_grava_nada() {
        var result = service(active).probe("RabbitMQ eventos", "pt").orElseThrow();

        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) result.get("nodes");
        assertThat(nodes).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(nodes).extracting(n -> n.get("rank")).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, nodes.size()).boxed().toList());
        assertThat(result).containsEntry("mode", "bm25");
        assertThat(heat.records).isZero();
        var known = active.chunks().stream().map(c -> c.sourceRef()).toList();
        assertThat(nodes).extracting(n -> (String) n.get("id")).allSatisfy(id -> assertThat(known).contains(id));
    }

    @Test
    void a_sonda_de_atividade_recente_mostra_o_documento_que_compara_os_repositorios_na_frente() {
        var activity = new br.com.gilvanborges.javai.ai.core.chunk.Chunk("pt:act", br.com.gilvanborges.javai.ai.core.chunk.SourceKind.GITHUB_PROFILE,
                "github-activity:dono", "pt", "Atividade recente", "O último commit foi no controlei", "https://github.com/dono");
        var chunks = new java.util.ArrayList<>(active.chunks());
        chunks.add(activity);
        var index = new ActiveIndex(1, "m", chunks, null, List.of(), new br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps());

        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) service(index).probe("qual foi o último commit dele?", "pt").orElseThrow().get("nodes");

        assertThat(nodes.get(0)).containsEntry("id", "github-activity:dono").containsEntry("rank", 1);
    }

    @Test
    void o_corpo_so_tem_conteudo_publico_do_indice_e_numeros() {
        heat.node(TODAY.minusDays(2), ref("project:"), 4);

        var body = service(active).snapshot().orElseThrow();

        // Nada além dos campos conhecidos: não há lugar para texto de pergunta no formato de resposta.
        assertThat(body.keySet()).containsExactlyInAnyOrder("windowDays", "minHits", "delayDays", "from", "to", "updatedAt",
                "answered", "hasVectors", "nodes", "groups", "branches", "edges");
        assertThat(list(body, "nodes").get(0).keySet()).containsExactlyInAnyOrder("id", "title", "kind", "group", "x", "y", "hits");
    }
}
