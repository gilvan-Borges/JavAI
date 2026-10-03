package br.com.gilvanborges.javai.ai.api.usage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Percentis de latência por etapa do pipeline (spec 06, pendência 3), em memória: as últimas {@value #WINDOW}
 * medições de cada etapa, num anel. Nada identifica o visitante; só durações em milissegundos, para o painel.
 */
public final class LatencyStats {

    private static final int WINDOW = 512;

    /** p50, p90 e quantas medições entraram desde o boot. */
    public record Percentiles(int p50, int p90, long count) {
    }

    private static final class Ring {
        final int[] values = new int[WINDOW];
        long count;

        synchronized void add(int ms) {
            values[(int) (count % WINDOW)] = ms;
            count++;
        }

        synchronized Percentiles percentiles() {
            int size = (int) Math.min(count, WINDOW);
            if (size == 0) {
                return new Percentiles(0, 0, 0);
            }
            int[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return new Percentiles(sorted[(size - 1) / 2], sorted[Math.min(size - 1, size * 9 / 10)], count);
        }
    }

    private final Map<String, Ring> steps = new ConcurrentHashMap<>();

    public void record(String step, int ms) {
        steps.computeIfAbsent(step, s -> new Ring()).add(ms);
    }

    /** Uma linha por etapa, na ordem do pipeline. Etapa sem medição não aparece. */
    public Map<String, Percentiles> snapshot() {
        var out = new LinkedHashMap<String, Percentiles>();
        for (String step : new String[]{"classify", "search", "model", "total"}) {
            Ring ring = steps.get(step);
            if (ring != null) {
                out.put(step, ring.percentiles());
            }
        }
        return out;
    }
}
