package br.com.gilvanborges.javai.ai.api.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cache em memória de respostas prontas (spec 06, pendência 2): a mesma pergunta, repetida, não paga o modelo de
 * novo. A chave é o SHA-256 da pergunta normalizada + idioma + versão do índice + versão do prompt, então qualquer
 * mudança no conteúdo ou no prompt invalida tudo sozinha. Só entra aqui resposta que passou pela guarda de saída, e
 * só para conversa sem histórico (com histórico o prompt muda). Nada é gravado em disco: a pergunta vira hash e o
 * cache morre com o processo.
 */
final class AnswerCache {

    /** O que é preciso para reapresentar a resposta como se fosse nova (menos o turn, que é assinado na hora). */
    record Entry(String text, List<Map<String, Object>> sources, String model, String category, String tier, List<String> refs) {
        /** Sem as origens (testes e usos antigos): a resposta do cache não soma no calor do cérebro. */
        Entry(String text, List<Map<String, Object>> sources, String model, String category, String tier) {
            this(text, sources, model, category, tier, List.of());
        }
    }

    private record Timed(Entry entry, Instant until) {
    }

    private static final int MAX_ENTRIES = 256;
    private static final Duration TTL = Duration.ofHours(6);

    private final Clock clock;
    private final Map<String, Timed> entries = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Timed> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    AnswerCache(Clock clock) {
        this.clock = clock;
    }

    String key(String question, String lang, long indexVersion) {
        String normalized = question.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return sha256(lang + "|" + indexVersion + "|" + PromptBuilder.VERSION + "|" + normalized);
    }

    synchronized Optional<Entry> get(String key) {
        Timed timed = entries.get(key);
        if (timed == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(timed.until())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(timed.entry());
    }

    synchronized void put(String key, Entry entry) {
        entries.put(key, new Timed(entry, clock.instant().plus(TTL)));
    }

    synchronized int size() {
        return entries.size();
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
