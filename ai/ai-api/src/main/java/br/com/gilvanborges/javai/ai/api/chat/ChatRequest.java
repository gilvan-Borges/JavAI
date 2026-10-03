package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.text.TextCleaner;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * O corpo de {@code POST /v1/chat} (spec 03). A leitura é estrita: campo desconhecido (um {@code "model"}, por
 * exemplo) é erro 400, nunca é ignorado, e o corpo inteiro tem no máximo 16 KB.
 */
public record ChatRequest(String question, String lang, String turnstileToken, List<Turn> history) {

    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final int MIN_QUESTION = 2;
    public static final int MAX_QUESTION = 500;
    public static final int MAX_HISTORY = 4;
    private static final int MAX_TOKEN = 2048;

    /** Um turno guardado pelo navegador; só vale com a assinatura do servidor (ver {@link HistorySigner}). */
    public record Turn(String turnId, String question, String answer, String issuedAt, String sig) {
    }

    /** Erro de validação, com a mensagem que vai para o visitante. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Lê e valida. A pergunta volta normalizada (NFKC, sem caracteres de controle). */
    public static ChatRequest parse(byte[] body) {
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new Invalid("The body must have between 1 byte and 16 KB.");
        }
        ChatRequest raw;
        try {
            raw = STRICT.readValue(body, ChatRequest.class);
        } catch (RuntimeException e) {
            throw new Invalid("Malformed JSON or unknown field.");
        }
        if (raw == null || raw.question == null) {
            throw new Invalid("question is required.");
        }
        String question = TextCleaner.clean(raw.question, false);
        if (question.length() < MIN_QUESTION || question.length() > MAX_QUESTION) {
            throw new Invalid("question must have 2 to 500 characters.");
        }
        if (raw.lang != null && !raw.lang.equals("pt") && !raw.lang.equals("en")) {
            throw new Invalid("lang must be \"pt\" or \"en\".");
        }
        if (raw.turnstileToken == null || raw.turnstileToken.isBlank() || raw.turnstileToken.length() > MAX_TOKEN) {
            throw new Invalid("turnstileToken is required.");
        }
        List<Turn> history = raw.history == null ? List.of() : raw.history;
        if (history.size() > MAX_HISTORY) {
            throw new Invalid("history has at most 4 turns.");
        }
        for (Turn turn : history) {
            if (turn == null || turn.turnId == null || turn.question == null || turn.answer == null
                    || turn.issuedAt == null || turn.sig == null) {
                throw new Invalid("Every history turn needs turnId, question, answer, issuedAt and sig.");
            }
        }
        return new ChatRequest(question, raw.lang, raw.turnstileToken, List.copyOf(history));
    }
}
