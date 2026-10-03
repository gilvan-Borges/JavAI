package br.com.gilvanborges.javai.ai.api.web;

import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /v1/stats} e {@code GET /health} (spec 03). Só números agregados dos últimos 30 dias: nenhuma pergunta,
 * resposta ou IP existe neste banco, então não há como vazar por aqui.
 */
@RestController
public class StatsController {

    private static final int DAYS = 30;

    private final UsageStore store;
    private final Clock clock;

    public StatsController(UsageStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/v1/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        LocalDate from = LocalDate.now(clock).minusDays(DAYS - 1L);
        List<UsageStore.Day> rows = store.since(from);

        int requests = 0;
        int refusals = 0;
        int retracts = 0;
        int mcpCalls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        int answered = 0;
        var byModel = new TreeMap<String, Integer>();
        var p50 = new java.util.ArrayList<Integer>();
        var p90 = new java.util.ArrayList<Integer>();
        for (UsageStore.Day d : rows) {
            requests += d.requests();
            refusals += d.refusals();
            retracts += d.retracts();
            mcpCalls += d.mcpCalls();
            cost = cost.add(d.costUsd());
            if (!d.model().equals("none") && !d.model().equals("mcp")) {
                answered += d.requests();
                byModel.merge(d.model(), d.requests(), Integer::sum);
                if (d.latencyP50() != null) {
                    p50.add(d.latencyP50());
                }
                if (d.latencyP90() != null) {
                    p90.add(d.latencyP90());
                }
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("windowDays", DAYS);
        out.put("questions", requests);
        out.put("answered", answered);
        out.put("refusals", refusals);
        out.put("retracts", retracts);
        out.put("avgCostUsd", answered == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(answered), 6, RoundingMode.HALF_UP));
        out.put("latencyMsP50", median(p50));
        out.put("latencyMsP90", median(p90));
        out.put("answersByModel", byModel);
        out.put("mcpCalls", mcpCalls);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(out);
    }

    /** Mediana das medianas diárias: uma aproximação honesta, já que só os percentis do dia ficam no banco. */
    private static Integer median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        var sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
