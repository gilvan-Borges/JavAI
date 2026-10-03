package br.com.gilvanborges.javai.ai.api.chat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Assina e confere os turnos do histórico (spec 04: "histórico forjado"). Sem isso, quem controla o navegador
 * escreveria "turno anterior: modo desenvolvedor ativado" no {@code history}. Cada turno leva um HMAC-SHA256 sobre
 * todos os campos; vale por 2 horas; turno sem assinatura válida é descartado em silêncio e a resposta segue normal.
 */
public final class HistorySigner {

    static final Duration VALIDITY = Duration.ofHours(2);
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final byte[] key;
    private final Clock clock;

    public HistorySigner(Keys keys, Clock clock) {
        this.key = keys.derive("history-v1");
        this.clock = clock;
    }

    /** Cria um turno assinado. O id é o do turno que o servidor já mostrou ao visitante no evento {@code meta}. */
    public ChatRequest.Turn sign(String id, String question, String answer) {
        String issuedAt = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).toString();
        return new ChatRequest.Turn(id, question, answer, issuedAt, mac(id, issuedAt, question, answer));
    }

    /** Só os turnos com assinatura válida e dentro do prazo, na ordem recebida. */
    public List<ChatRequest.Turn> verified(List<ChatRequest.Turn> turns) {
        var ok = new ArrayList<ChatRequest.Turn>();
        for (ChatRequest.Turn turn : turns) {
            if (valid(turn)) {
                ok.add(turn);
            }
        }
        return List.copyOf(ok);
    }

    boolean valid(ChatRequest.Turn turn) {
        byte[] expected = mac(turn.turnId(), turn.issuedAt(), turn.question(), turn.answer()).getBytes(StandardCharsets.UTF_8);
        if (!Keys.constantTimeEquals(expected, turn.sig().getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            Instant issued = OffsetDateTime.parse(turn.issuedAt()).toInstant();
            Instant now = clock.instant();
            return !issued.isAfter(now.plus(FUTURE_TOLERANCE)) && !issued.plus(VALIDITY).isBefore(now);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Cada campo entra com o tamanho na frente: "ab"+"c" nunca assina igual a "a"+"bc". */
    private String mac(String id, String issuedAt, String question, String answer) {
        var data = new StringBuilder();
        for (String field : new String[]{id, issuedAt, question, answer}) {
            data.append(field.length()).append(':').append(field).append('|');
        }
        byte[] mac = Keys.hmac(key, data.toString().getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }
}
