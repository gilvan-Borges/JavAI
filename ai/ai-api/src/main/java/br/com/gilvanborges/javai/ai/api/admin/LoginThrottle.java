package br.com.gilvanborges.javai.ai.api.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Freio de força bruta no login: depois de {@code maxFailures} erros dentro da janela, aquele visitante fica bloqueado
 * por {@code lockFor}, mesmo que acerte a senha. Chaveado pelo hash estável do IP, só em memória.
 */
public final class LoginThrottle {

    private static final int MAX_KEYS = 10_000;

    private final Clock clock;
    private final int maxFailures;
    private final Duration lockFor;
    private final Map<String, State> states = new HashMap<>();

    private static final class State {
        int failures;
        Instant windowStart;
        Instant lockedUntil;
    }

    public LoginThrottle(Clock clock, int maxFailures, Duration lockFor) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.lockFor = lockFor;
    }

    public synchronized Optional<Duration> lockedFor(String key) {
        State s = states.get(key);
        Instant now = clock.instant();
        if (s == null || s.lockedUntil == null || !s.lockedUntil.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, s.lockedUntil));
    }

    public synchronized void recordFailure(String key) {
        Instant now = clock.instant();
        if (states.size() >= MAX_KEYS) {
            states.clear();
        }
        State s = states.computeIfAbsent(key, k -> new State());
        if (s.windowStart == null || s.windowStart.plus(lockFor).isBefore(now)) {
            s.windowStart = now;
            s.failures = 0;
        }
        if (++s.failures >= maxFailures) {
            s.lockedUntil = now.plus(lockFor);
        }
    }

    public synchronized void reset(String key) {
        states.remove(key);
    }
}
