package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.EventSink;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O resultado de uma pergunta de teste no painel "Treinar": o que o pipeline real devolveu, juntado a partir dos
 * mesmos eventos que o visitante recebe pelo SSE, mais os trechos que a busca recuperou. Nada é gravado.
 */
record TrainRun(String question, String lang, String answer, List<Map<String, Object>> sources,
                List<Map<String, Object>> traces, Map<String, Object> meta, Map<String, Object> usage,
                String refusal, String error, boolean retracted, List<HybridSearcher.Hit> hits, boolean vector) {

    /** Junta os eventos de {@code ChatService.answer} na ordem em que chegam. */
    static final class Collector implements EventSink {

        final StringBuilder answer = new StringBuilder();
        final List<Map<String, Object>> sources = new ArrayList<>();
        final List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> meta = Map.of();
        Map<String, Object> usage = Map.of();
        String refusal;
        String error;
        boolean retracted;

        @Override
        @SuppressWarnings("unchecked")
        public boolean send(String event, Object data) {
            switch (event) {
                case "delta" -> answer.append(((Map<String, Object>) data).get("text"));
                case "sources" -> ((List<Map<String, Object>>) data).forEach(sources::add);
                case "trace" -> traces.add(new LinkedHashMap<>((Map<String, Object>) data));
                case "meta" -> meta = new LinkedHashMap<>((Map<String, Object>) data);
                case "done" -> usage = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) data).get("usage"));
                case "refusal" -> refusal = String.valueOf(((Map<String, Object>) data).get("text"));
                case "retract" -> retracted = true;
                case "error" -> error = String.valueOf(((Map<String, Object>) data).get("message"));
                default -> { }
            }
            return true;
        }

        TrainRun finish(String question, String lang, List<HybridSearcher.Hit> hits, boolean vector) {
            return new TrainRun(question, lang, retracted ? "" : answer.toString().strip(), List.copyOf(sources),
                    List.copyOf(traces), meta, usage, refusal, error, retracted, hits, vector);
        }
    }
}
