package br.com.gilvanborges.javai.ai.api.brain;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.HeatStore;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import br.com.gilvanborges.javai.ai.core.brain.BrainLayout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monta o "cérebro": o mapa dos conhecimentos da IA (pontos = origens públicas do índice, perto = parecidos) com o
 * calor de por onde a busca andou e as ramificações (o que se escolhe junto). Tudo vem dos contadores agregados, que não
 * têm pergunta, resposta, IP nem horário (ver {@link HeatStore}).
 *
 * <p>Regras de privacidade, em ordem:
 * <ul>
 *   <li>só aparece número de um trecho ou par escolhido ao menos {@code minHits} vezes no período;</li>
 *   <li>os {@code delayDays} dias mais recentes não entram, então nada reflete uma pergunta de agora;</li>
 *   <li>o mapa montado é reaproveitado por {@code cacheMinutes}.</li>
 * </ul>
 */
@Service
public class BrainService {

    private static final int TOP_CHILDREN = 5;
    private static final int MAX_EDGES = 80;

    private record Cached(long versionId, Instant at, Map<String, Object> body) {
    }

    private final IndexHolder index;
    private final ObjectProvider<HeatStore> heat;
    private final ObjectProvider<UsageStore> usage;
    private final QueryEmbedder embedder;
    private final Clock clock;
    private final BrainProperties props;
    private volatile Cached cached;

    public BrainService(IndexHolder index, ObjectProvider<HeatStore> heat, ObjectProvider<UsageStore> usage,
                        QueryEmbedder embedder, Clock clock, BrainProperties props) {
        this.index = index;
        this.heat = heat;
        this.usage = usage;
        this.embedder = embedder;
        this.clock = clock;
        this.props = props;
    }

    public int probeLimitPerMinute() {
        return props.probePerMinute();
    }

    /** @return vazio se ainda não há índice ativo */
    public Optional<Map<String, Object>> snapshot() {
        ActiveIndex active = index.current().orElse(null);
        if (active == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Cached hit = cached;
        if (hit != null && hit.versionId() == active.versionId()
                && Duration.between(hit.at(), now).compareTo(Duration.ofMinutes(props.cacheMinutes())) < 0) {
            return Optional.of(hit.body());
        }
        Map<String, Object> body = build(active, now);
        cached = new Cached(active.versionId(), now, body);
        return Optional.of(body);
    }

    Map<String, Object> build(ActiveIndex active, Instant now) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.minusDays(props.delayDays());
        LocalDate from = to.minusDays(props.windowDays() - 1L);
        HeatStore store = heat.getIfAvailable(() -> HeatStore.NOOP);
        HeatStore.Heat counts = store.between(from, to);
        List<BrainLayout.Node> layout = active.brainLayout();

        var nodes = new ArrayList<Map<String, Object>>();
        var groups = new LinkedHashMap<String, GroupAcc>();
        var known = new LinkedHashSet<String>();
        for (BrainLayout.Node n : layout) {
            known.add(n.id());
            int raw = counts.nodes().getOrDefault(n.id(), 0);
            Integer shown = raw >= props.minHits() ? raw : null;
            var node = new LinkedHashMap<String, Object>();
            node.put("id", n.id());
            node.put("title", n.title());
            node.put("kind", n.kind().db());
            node.put("group", n.group());
            node.put("x", round(n.x()));
            node.put("y", round(n.y()));
            node.put("hits", shown);
            nodes.add(node);
            GroupAcc g = groups.computeIfAbsent(n.group(), k -> new GroupAcc(k, n.groupLabel(), n.kind().db()));
            g.nodes++;
            if (shown != null) {
                g.hits += shown;
                g.top.add(new Child(n.id(), n.title(), shown));
            }
        }

        var groupList = new ArrayList<Map<String, Object>>();
        var branches = new ArrayList<Map<String, Object>>();
        for (GroupAcc g : groups.values()) {
            var gm = new LinkedHashMap<String, Object>();
            gm.put("key", g.key);
            gm.put("label", g.label);
            gm.put("kind", g.kind);
            gm.put("nodes", g.nodes);
            gm.put("hits", g.hits > 0 ? g.hits : null);
            groupList.add(gm);
            if (g.hits > 0) {
                var bm = new LinkedHashMap<String, Object>();
                bm.put("group", g.key);
                bm.put("label", g.label);
                bm.put("kind", g.kind);
                bm.put("hits", g.hits);
                bm.put("top", g.top.stream().sorted(Comparator.comparingInt(Child::hits).reversed().thenComparing(Child::title))
                        .limit(TOP_CHILDREN).map(c -> Map.<String, Object>of("id", c.id, "title", c.title, "hits", c.hits)).toList());
                branches.add(bm);
            }
        }
        branches.sort(Comparator.comparingInt((Map<String, Object> b) -> (Integer) b.get("hits")).reversed());

        var edges = new ArrayList<Map<String, Object>>();
        counts.pairs().entrySet().stream()
                .filter(e -> e.getValue() >= props.minHits() && known.contains(e.getKey().a()) && known.contains(e.getKey().b()))
                .sorted(Map.Entry.<HeatStore.Pair, Integer>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().a()).thenComparing(e -> e.getKey().b()))
                .limit(MAX_EDGES)
                .forEach(e -> edges.add(Map.of("a", e.getKey().a(), "b", e.getKey().b(), "hits", e.getValue())));

        var out = new LinkedHashMap<String, Object>();
        out.put("windowDays", props.windowDays());
        out.put("minHits", props.minHits());
        out.put("delayDays", props.delayDays());
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("updatedAt", now.toString());
        out.put("answered", answered(from, to));
        out.put("hasVectors", active.hasVectors());
        out.put("nodes", nodes);
        out.put("groups", groupList);
        out.put("branches", branches);
        out.put("edges", edges);
        return out;
    }

    /** Respostas geradas no período, do mesmo contador do /v1/stats (cache e recusas não contam como modelo). */
    private int answered(LocalDate from, LocalDate to) {
        UsageStore store = usage.getIfAvailable();
        if (store == null) {
            return 0;
        }
        return store.since(from).stream()
                .filter(d -> !d.day().isAfter(to) && !d.model().equals("none") && !d.model().equals("mcp"))
                .mapToInt(UsageStore.Day::requests).sum();
    }

    /**
     * "Onde minha pergunta cai?": só a busca, sem modelo de chat, sem custo e SEM gravar nada. Devolve as origens que a
     * busca escolheria, na ordem. A pergunta não vai para log, contador nem cache.
     */
    public Optional<Map<String, Object>> probe(String question, String lang) {
        ActiveIndex active = index.current().orElse(null);
        if (active == null) {
            return Optional.empty();
        }
        float[] vector = active.hasVectors() ? embedder.embed(question, active.embeddingModel()).orElse(null) : null;
        var refs = new LinkedHashSet<String>();
        active.search(question, vector, lang, 5).forEach(h -> refs.add(h.chunk().sourceRef()));
        var picked = new ArrayList<Map<String, Object>>();
        int rank = 1;
        for (String ref : refs) {
            picked.add(Map.of("id", ref, "rank", rank++));
        }
        return Optional.of(Map.of("mode", vector != null ? "hybrid" : "bm25", "nodes", picked));
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static final class GroupAcc {
        final String key;
        final String label;
        final String kind;
        int nodes;
        int hits;
        final List<Child> top = new ArrayList<>();

        GroupAcc(String key, String label, String kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }
    }

    private record Child(String id, String title, int hits) {
    }
}
