package br.com.gilvanborges.javai.ai.api.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registra os contadores do dia e mantém o gasto do dia em memória para o disjuntor (spec 01, "Custos e limites").
 * Tudo é agregado: valores, nunca texto. Se o banco falhar, o chat segue e o gasto continua contado em memória;
 * o que não puder ser gravado é perdido, e isso é preferível a derrubar uma resposta.
 */
@Component
public class UsageRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageStore store;
    private final Clock clock;
    private final AtomicInteger pendingMcpCalls = new AtomicInteger();

    private LocalDate day;
    private BigDecimal spent = BigDecimal.ZERO;
    private final Map<String, List<Integer>> latencies = new HashMap<>();

    public UsageRecorder(UsageStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** O gasto de hoje, em dólares (horário de São Paulo). */
    public synchronized BigDecimal spentToday() {
        rollover();
        return spent;
    }

    public synchronized void answer(String model, long tokensIn, long tokensOut, BigDecimal cost, int latencyMs, boolean retracted) {
        rollover();
        spent = spent.add(cost);
        List<Integer> list = latencies.computeIfAbsent(model, m -> new ArrayList<>());
        list.add(latencyMs);
        write(new UsageStore.Day(day, model, 1, 0, retracted ? 1 : 0, tokensIn, tokensOut, cost,
                percentile(list, 0.5), percentile(list, 0.9), 0));
    }

    public synchronized void refusal() {
        rollover();
        write(new UsageStore.Day(day, "none", 1, 1, 0, 0, 0, BigDecimal.ZERO, null, null, 0));
    }

    public void mcpCall() {
        pendingMcpCalls.incrementAndGet();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized void flushMcpCalls() {
        int calls = pendingMcpCalls.getAndSet(0);
        if (calls == 0) {
            return;
        }
        rollover();
        write(new UsageStore.Day(day, "mcp", 0, 0, 0, 0, 0, BigDecimal.ZERO, null, null, calls));
    }

    private void rollover() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            latencies.clear();
            try {
                spent = store.spentOn(today);
            } catch (RuntimeException e) {
                spent = BigDecimal.ZERO;
                LOG.warn("Não consegui ler o gasto do dia ({}); contando a partir de zero.", e.getClass().getSimpleName());
            }
        }
    }

    private void write(UsageStore.Day delta) {
        try {
            store.add(delta);
        } catch (RuntimeException e) {
            LOG.warn("Não consegui gravar os contadores ({}).", e.getClass().getSimpleName());
        }
    }

    static Integer percentile(List<Integer> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }
}
