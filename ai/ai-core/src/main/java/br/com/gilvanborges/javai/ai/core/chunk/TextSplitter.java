package br.com.gilvanborges.javai.ai.core.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide um texto em pedaços de até {@code maxChars}, cortando só em fronteiras naturais: primeiro parágrafos, depois
 * linhas e, em último caso, frases. Nada é cortado no meio de uma palavra.
 */
public final class TextSplitter {

    private TextSplitter() {
    }

    public static List<String> split(String text, int maxChars) {
        if (maxChars < 100) {
            throw new IllegalArgumentException("maxChars precisa ser pelo menos 100.");
        }
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            return List.of();
        }
        var pieces = new ArrayList<String>();
        for (String paragraph : clean.split("\\R\\s*\\R")) {
            for (String line : fitLines(paragraph.strip(), maxChars)) {
                pieces.add(line);
            }
        }
        return merge(pieces, maxChars);
    }

    /** Um parágrafo que cabe fica inteiro; um que não cabe é quebrado por linhas e, se preciso, por frases. */
    private static List<String> fitLines(String paragraph, int maxChars) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (String line : paragraph.split("\\R")) {
            for (String part : line.length() > maxChars ? sentences(line, maxChars) : List.of(line)) {
                if (!current.isEmpty() && current.length() + part.length() + 1 > maxChars) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(part);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static List<String> sentences(String line, int maxChars) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (String word : line.split(" ")) {
            if (!current.isEmpty() && current.length() + word.length() + 1 > maxChars) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** Junta pedaços vizinhos pequenos para não gerar trechos de uma linha só. */
    private static List<String> merge(List<String> pieces, int maxChars) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (String piece : pieces) {
            if (!current.isEmpty() && current.length() + piece.length() + 2 > maxChars) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(piece);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}
