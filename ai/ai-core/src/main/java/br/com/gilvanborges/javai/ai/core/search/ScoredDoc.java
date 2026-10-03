package br.com.gilvanborges.javai.ai.core.search;

/** Um documento (pela posição na lista de origem) e a nota dele numa busca. */
public record ScoredDoc(int doc, double score) {
}
