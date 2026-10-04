package br.com.gilvanborges.javai.ai.api.usage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Contadores do cérebro no banco da IA, com o papel {@code ai_reader} (que só soma e lê: não apaga). */
@Repository
@ConditionalOnProperty(name = "aiapi.db.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcHeatStore implements HeatStore {

    /** Mais que isso numa resposta não acontece (a busca devolve 5); o teto protege o número de pares (n²). */
    static final int MAX_REFS = 8;

    private static final String NODE = """
            INSERT INTO ops.knowledge_heat (day, source_ref, hits) VALUES (:day, :ref, 1)
            ON CONFLICT (day, source_ref) DO UPDATE SET hits = ops.knowledge_heat.hits + 1""";
    private static final String PAIR = """
            INSERT INTO ops.knowledge_pair (day, source_a, source_b, hits) VALUES (:day, :a, :b, 1)
            ON CONFLICT (day, source_a, source_b) DO UPDATE SET hits = ops.knowledge_pair.hits + 1""";
    private static final String NODES = """
            SELECT source_ref, sum(hits)::int AS hits FROM ops.knowledge_heat
            WHERE day BETWEEN :from AND :to GROUP BY source_ref""";
    private static final String PAIRS = """
            SELECT source_a, source_b, sum(hits)::int AS hits FROM ops.knowledge_pair
            WHERE day BETWEEN :from AND :to GROUP BY source_a, source_b""";

    private final JdbcClient jdbc;

    public JdbcHeatStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(LocalDate day, Collection<String> sourceRefs) {
        // Ordenado e sem repetição: cada origem conta uma vez por resposta e cada par também.
        List<String> refs = new ArrayList<>(new TreeSet<>(sourceRefs.stream()
                .filter(r -> r != null && !r.isBlank() && r.length() <= 300).toList()));
        if (refs.size() > MAX_REFS) {
            refs = refs.subList(0, MAX_REFS);
        }
        for (String ref : refs) {
            jdbc.sql(NODE).param("day", day).param("ref", ref).update();
        }
        for (int i = 0; i < refs.size(); i++) {
            for (int j = i + 1; j < refs.size(); j++) {
                jdbc.sql(PAIR).param("day", day).param("a", refs.get(i)).param("b", refs.get(j)).update();
            }
        }
    }

    @Override
    public Heat between(LocalDate from, LocalDate to) {
        Map<String, Integer> nodes = new HashMap<>();
        jdbc.sql(NODES).param("from", from).param("to", to)
                .query((rs, n) -> nodes.put(rs.getString("source_ref"), rs.getInt("hits"))).list();
        Map<Pair, Integer> pairs = new HashMap<>();
        jdbc.sql(PAIRS).param("from", from).param("to", to)
                .query((rs, n) -> pairs.put(new Pair(rs.getString("source_a"), rs.getString("source_b")), rs.getInt("hits"))).list();
        return new Heat(Map.copyOf(nodes), Map.copyOf(pairs));
    }
}
