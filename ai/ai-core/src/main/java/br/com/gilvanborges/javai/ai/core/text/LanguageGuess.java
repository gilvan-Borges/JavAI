package br.com.gilvanborges.javai.ai.core.text;

import java.util.Set;

/**
 * Chute simples de idioma (pt ou en) por palavras muito comuns. Serve só para marcar o idioma de um README; não é
 * um detector geral. Em caso de empate ou texto sem pistas, devolve inglês, que é o idioma da maior parte do código.
 */
public final class LanguageGuess {

    private static final Set<String> PT = Set.of(
            "de", "do", "da", "dos", "das", "em", "para", "com", "que", "uma", "um", "não", "nao", "são", "sao", "você",
            "voce", "como", "por", "mais", "foi", "ser", "está", "esta", "também", "tambem", "sobre", "projeto", "rodar");

    private static final Set<String> EN = Set.of(
            "the", "of", "and", "to", "in", "is", "for", "with", "that", "this", "are", "you", "how", "from", "on", "as",
            "it", "be", "an", "or", "run", "build", "project", "install", "usage");

    private LanguageGuess() {
    }

    public static String of(String text) {
        int pt = 0;
        int en = 0;
        for (String word : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+")) {
            if (PT.contains(word)) {
                pt++;
            }
            if (EN.contains(word)) {
                en++;
            }
        }
        return pt > en ? "pt" : "en";
    }
}
