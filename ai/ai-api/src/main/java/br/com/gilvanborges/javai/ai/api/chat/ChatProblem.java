package br.com.gilvanborges.javai.ai.api.chat;

/** Erro de uma chamada ao chat que acontece antes do streaming (spec 03, "Erros"). Vira Problem Details (RFC 9457). */
public final class ChatProblem extends RuntimeException {

    private final int status;
    private final String type;
    private final String title;
    private final long retryAfterSeconds;

    public ChatProblem(int status, String type, String title, String detail, long retryAfterSeconds) {
        super(detail, null, false, false);
        this.status = status;
        this.type = type;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    /** 0 quando não há o que esperar. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
