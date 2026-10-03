package br.com.gilvanborges.javai.ai.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Divide o texto normalizado em termos para o BM25. Palavras muito comuns (português e inglês) são descartadas, e
 * termos técnicos como "c#", "node.js" ou "java21" ficam inteiros o suficiente para casar nos dois lados.
 */
public final class Tokenizer {

    // List.of + Set.copyOf: palavras como "do", "as" e "me" existem nos dois idiomas, e Set.of recusa duplicados.
    private static final Set<String> STOPWORDS = Set.copyOf(java.util.List.of(
            // português
            "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
            "por", "para", "com", "sem", "sobre", "e", "ou", "que", "se", "ao", "aos", "ele", "ela", "eles", "elas", "seu",
            "sua", "seus", "suas", "foi", "ser", "sao", "tem", "ja", "mais", "como", "qual", "quais", "quem", "onde", "pelo",
            "pela", "isso", "esse", "essa", "este", "esta", "eu", "me", "meu", "minha",
            // inglês
            "the", "an", "of", "in", "on", "at", "to", "for", "with", "without", "and", "or", "is", "are", "was", "were", "be",
            "been", "it", "its", "his", "her", "he", "she", "they", "them", "this", "that", "these", "those", "has", "have",
            "had", "do", "does", "did", "what", "which", "who", "where", "how", "by", "from", "as", "i", "my", "me"));

    private Tokenizer() {
    }

    public static List<String> tokens(String text) {
        String normalized = TextNormalizer.normalize(text);
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c) || (c == '#' || c == '+') && !current.isEmpty()) {
                current.append(c);
            } else {
                flush(current, out);
            }
        }
        flush(current, out);
        return out;
    }

    private static void flush(StringBuilder current, List<String> out) {
        if (current.isEmpty()) {
            return;
        }
        String token = current.toString();
        current.setLength(0);
        // Termo de um caractere só vale se for dígito ou símbolo de linguagem ("c#", "r" não): evita ruído.
        if (token.length() < 2 && !Character.isDigit(token.charAt(0))) {
            return;
        }
        if (!STOPWORDS.contains(token)) {
            out.add(token);
        }
    }
}
