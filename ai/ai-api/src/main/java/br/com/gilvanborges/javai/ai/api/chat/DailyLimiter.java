package br.com.gilvanborges.javai.ai.api.chat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Perguntas por dia por visitante. A chave já chega como hash com a chave do dia, então o mapa nunca tem IP e se
 * esvazia sozinho na virada do dia (horário de São Paulo).
 */
public final class DailyLimiter {

    private static final int MAX_KEYS = 50_000;

    private final int perDay;
    private final Clock clock;
    private final Map<String, Integer> counts = new HashMap<>();
    private LocalDate day;

    public DailyLimiter(int perDay, Clock clock) {
        this.perDay = perDay;
        this.clock = clock;
    }

    /** @return {@code true} se ainda cabe uma pergunta (e já a conta) */
    public synchronized boolean tryAcquire(String key) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day) || counts.size() >= MAX_KEYS) {
            counts.clear();
            day = today;
        }
        int used = counts.merge(key, 1, Integer::sum);
        return used <= perDay;
    }
}
