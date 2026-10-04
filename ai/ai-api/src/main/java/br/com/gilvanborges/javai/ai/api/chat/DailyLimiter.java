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
        if (perDay <= 0) {
            return true;
        }
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            counts.clear(); // virada do dia: é a única hora de zerar
            day = today;
        }
        if (counts.size() >= MAX_KEYS && !counts.containsKey(key)) {
            // Lotado: antes isto zerava todo mundo no meio do dia. Agora quem já é conhecido continua contado e o visitante
            // novo passa sem ficar registrado (falha aberta de propósito: o orçamento diário em dólar continua valendo).
            return true;
        }
        int used = counts.merge(key, 1, Integer::sum);
        return used <= perDay;
    }
}
