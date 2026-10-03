package br.com.gilvanborges.javai.ai.api.chat;

/** Para onde vão os eventos do stream (SSE). Devolve {@code false} quando o visitante já foi embora. */
public interface EventSink {

    boolean send(String event, Object data);
}
