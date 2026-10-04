package br.com.gilvanborges.javai.ai.api.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Bloqueio de quem tenta atacar o chat. Duas regras, as duas só sobre o que o Jev chamou de {@code ataque}:
 * uma tentativa com confiança alta ({@code banConfidence}) bloqueia na hora; tentativas de confiança menor contam
 * como "strike", e o último strike do limite também bloqueia. Pergunta fora do escopo (um poema, por exemplo) nunca
 * conta: quem erra de assunto não é atacante. O bloqueio dura {@code banFor}, vale para o IP e fica só em memória,
 * sob um hash estável (nunca o IP): reiniciar o serviço zera os bloqueios. {@code banFor} zero desliga tudo, que é o
 * que a instância de avaliação usa para a bateria de ataques não se bloquear.
 */
public final class AbuseGuard {

    private static final int DEFAULT_MAX_KEYS = 20_000;

    private final int maxKeys;
    private final Clock clock;
    private final Duration banFor;
    private final double banConfidence;
    private final int strikesToBan;
    private final Map<String, State> states = new HashMap<>();

    private static final class State {
        int strikes;
        Instant windowStart;
        Instant bannedUntil;
    }

    public AbuseGuard(Clock clock, Duration banFor, double banConfidence, int strikesToBan) {
        this(clock, banFor, banConfidence, strikesToBan, DEFAULT_MAX_KEYS);
    }

    /** O teto de visitantes acompanhados é injetável para o teste de transbordo não precisar de 20 mil chaves. */
    AbuseGuard(Clock clock, Duration banFor, double banConfidence, int strikesToBan, int maxKeys) {
        this.maxKeys = maxKeys;
        this.clock = clock;
        this.banFor = banFor;
        this.banConfidence = banConfidence;
        this.strikesToBan = strikesToBan;
    }

    public boolean enabled() {
        return !banFor.isZero() && !banFor.isNegative();
    }

    /** Por quanto tempo ainda dura o bloqueio deste visitante, ou vazio se ele não está bloqueado. */
    public synchronized Optional<Duration> blockedFor(String key) {
        if (!enabled()) {
            return Optional.empty();
        }
        State s = states.get(key);
        Instant now = clock.instant();
        if (s == null || s.bannedUntil == null || !s.bannedUntil.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, s.bannedUntil));
    }

    /** Quantos visitantes estão bloqueados neste momento. Só a contagem: o serviço não guarda quem são. */
    public synchronized int blockedCount() {
        Instant now = clock.instant();
        return (int) states.values().stream().filter(s -> s.bannedUntil != null && s.bannedUntil.isAfter(now)).count();
    }

    /** Registra uma tentativa de ataque barrada pelo roteador. @return {@code true} se este visitante acabou de ser bloqueado */
    public synchronized boolean recordAttack(String key, double confidence) {
        if (!enabled()) {
            return false;
        }
        Instant now = clock.instant();
        evictExpired(now);
        if (states.size() >= maxKeys && !states.containsKey(key)) {
            // Lotado de bloqueios ativos (só acontece sob ataque em massa): não há o que despejar sem soltar alguém.
            // Quem já está bloqueado continua; o visitante novo não ganha ficha nem strike até abrir espaço.
            return false;
        }
        State s = states.computeIfAbsent(key, k -> new State());
        // Os strikes valem pela mesma janela do bloqueio: depois dela, a ficha limpa.
        if (s.windowStart == null || s.windowStart.plus(banFor).isBefore(now)) {
            s.windowStart = now;
            s.strikes = 0;
        }
        s.strikes++;
        if (confidence >= banConfidence || s.strikes >= strikesToBan) {
            s.bannedUntil = now.plus(banFor);
            return true;
        }
        return false;
    }

    /**
     * Abre espaço sem nunca soltar um bloqueio ativo: primeiro descarta o que já acabou; se ainda estiver cheio, descarta
     * quem só tem strikes (sem bloqueio), do mais antigo para o mais novo. Antes isto era um clear(), e uma enxurrada de
     * IPs diferentes apagava justamente os bloqueios de 12 h que a defesa existe para manter.
     */
    private void evictExpired(Instant now) {
        if (states.size() < maxKeys) {
            return;
        }
        states.values().removeIf(s -> (s.bannedUntil == null || !s.bannedUntil.isAfter(now))
                && (s.windowStart == null || s.windowStart.plus(banFor).isBefore(now)));
        if (states.size() < maxKeys) {
            return;
        }
        var onlyStrikes = new java.util.ArrayList<Map.Entry<String, State>>();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.bannedUntil == null || !s.bannedUntil.isAfter(now)) {
                onlyStrikes.add(e);
            }
        }
        onlyStrikes.sort(java.util.Comparator.comparing(e -> e.getValue().windowStart == null ? Instant.MIN : e.getValue().windowStart));
        int toDrop = Math.max(1, onlyStrikes.size() / 2);
        for (int i = 0; i < toDrop && i < onlyStrikes.size(); i++) {
            states.remove(onlyStrikes.get(i).getKey());
        }
    }
}
