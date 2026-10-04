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

    private static final int DEFAULT_MAX_KEYS = 10_000;

    private final int maxKeys;
    private final int perMinute;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        this(perMinute, clock, DEFAULT_MAX_KEYS);
    }

    /** O teto de chaves é injetável para o teste de transbordo. */
    RateLimiter(int perMinute, Clock clock, int maxKeys) {
        this.maxKeys = maxKeys;
        if (perMinute < 1) {
            throw new IllegalArgumentException("O limite por minuto precisa ser positivo.");
        }
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** @return 0 se a chamada pode seguir; senão, em quantos segundos tentar de novo */
    public synchronized long tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() >= maxKeys) {
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
            if (projected(b, now) >= perMinute) {
                it.remove();
            }
        }
        // Ainda cheio (tráfego de muitos IPs diferentes): despeja a metade com a ficha mais cheia, em vez de zerar tudo.
        // Esquecer um balde quase cheio quase não muda nada (ele voltaria cheio mesmo); o que não pode acontecer é devolver
        // a ficha cheia a quem está esgotado, que é justamente quem o limite existe para segurar.
        if (buckets.size() >= maxKeys) {
            var byTokens = new java.util.ArrayList<Map.Entry<String, Bucket>>(buckets.entrySet());
            byTokens.sort(java.util.Comparator.comparingDouble((Map.Entry<String, Bucket> e) -> projected(e.getValue(), now)).reversed());
            for (int i = 0; i < Math.max(1, byTokens.size() / 2); i++) {
                buckets.remove(byTokens.get(i).getKey());
            }
        }
    }

    private double projected(Bucket b, long now) {
        return Math.min(perMinute, b.tokens + (now - b.lastMillis) * perMinute / 60_000.0);
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
