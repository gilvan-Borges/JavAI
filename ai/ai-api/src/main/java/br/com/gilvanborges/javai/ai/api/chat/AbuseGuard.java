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

    private static final int MAX_KEYS = 20_000;

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

    /** Registra uma tentativa de ataque barrada pelo roteador. @return {@code true} se este visitante acabou de ser bloqueado */
    public synchronized boolean recordAttack(String key, double confidence) {
        if (!enabled()) {
            return false;
        }
        Instant now = clock.instant();
        evictExpired(now);
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

    private void evictExpired(Instant now) {
        if (states.size() < MAX_KEYS) {
            return;
        }
        for (Iterator<State> it = states.values().iterator(); it.hasNext(); ) {
            State s = it.next();
            boolean banEnded = s.bannedUntil == null || !s.bannedUntil.isAfter(now);
            boolean windowEnded = s.windowStart == null || s.windowStart.plus(banFor).isBefore(now);
            if (banEnded && windowEnded) {
                it.remove();
            }
        }
        if (states.size() >= MAX_KEYS) {
            states.clear();
        }
    }
}
