package br.com.gilvanborges.javai.ai.api.chat;

/** O que o Jev disse da pergunta: a categoria e a confiança (0 a 1) nela. */
public record Classification(Category category, double confidence) {
}
