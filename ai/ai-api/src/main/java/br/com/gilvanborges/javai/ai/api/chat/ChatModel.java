package br.com.gilvanborges.javai.ai.api.chat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/** O modelo que escreve a resposta, em streaming. A implementação de produção é o OpenRouter. */
public interface ChatModel {

    /** O que a chamada consumiu; {@code model} é o modelo que de fato respondeu (pode ser o fallback). */
    record Usage(String model, long tokensIn, long tokensOut, BigDecimal costUsd) {
    }

    /**
     * Gera a resposta pedaço por pedaço.
     *
     * @param onDelta recebe cada pedaço de texto e devolve {@code false} para cancelar (a guarda de saída disparou ou o
     *                visitante saiu): a conexão com o provedor é fechada e nada mais é gerado
     */
    Usage stream(PromptBuilder.Prompt prompt, List<String> models, Predicate<String> onDelta) throws IOException;
}
