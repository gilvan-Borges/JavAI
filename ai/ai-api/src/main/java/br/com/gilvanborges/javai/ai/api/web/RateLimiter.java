package br.com.gilvanborges.javai.ai.api.web;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Balde de fichas por chave (o IP). Em memória e de uma instância só: o serviço roda num único contêiner. A
 * capacidade é o limite por minuto e as fichas voltam ao ritmo constante, então um cliente educado nunca percebe.
 * O mapa é limitado: passado o teto, as chaves ociosas (balde cheio) são descartadas.
 */
public final class RateLimiter {

    private static final int MAX_KEYS = 10_000;

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("O limite por minuto precisa ser positivo.");
        }
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** @return 0 se a chamada pode seguir; senão, em quantos segundos tentar de novo */
    public synchronized long tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() >= MAX_KEYS) {
            evictIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now));
        bucket.refill(now, perMinute);
        if (bucket.tokens >= 1) {
            bucket.tokens -= 1;
            return 0;
        }
        double missing = 1 - bucket.tokens;
        return Math.max(1, (long) Math.ceil(missing * 60.0 / perMinute));
    }

    private void evictIdle(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            Bucket b = it.next();
            b.refill(now, perMinute);
            if (b.tokens >= perMinute) {
                it.remove();
            }
        }
        // Ainda cheio (tráfego de muitos IPs diferentes): zera tudo em vez de crescer sem limite.
        if (buckets.size() >= MAX_KEYS) {
            buckets.clear();
        }
    }

    private static final class Bucket {
        double tokens;
        long lastMillis;

        Bucket(double tokens, long lastMillis) {
            this.tokens = tokens;
            this.lastMillis = lastMillis;
        }

        void refill(long now, int perMinute) {
            double add = (now - lastMillis) * perMinute / 60_000.0;
            tokens = Math.min(perMinute, tokens + add);
            lastMillis = now;
        }
    }
}
