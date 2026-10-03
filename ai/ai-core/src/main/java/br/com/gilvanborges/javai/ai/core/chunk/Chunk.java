package br.com.gilvanborges.javai.ai.core.chunk;

import java.util.Objects;

/**
 * Um trecho do conteúdo que a IA pode ler e citar.
 *
 * <p>O {@code title} é escrito em terceira pessoa ("Experiência de Gilvan Borges na Vibetex"), porque o conteúdo está
 * em primeira pessoa e as perguntas chegam em terceira: sem o título, a busca não aproxima os dois (spec 01).
 *
 * @param url o link que o servidor mostra como fonte. O modelo nunca escreve URL.
 */
public record Chunk(
        String id, SourceKind kind, String sourceRef, String lang, String title, String body, String url) {

    public Chunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (!lang.equals("pt") && !lang.equals("en")) {
            throw new IllegalArgumentException("lang precisa ser pt ou en.");
        }
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("url precisa ser https.");
        }
    }

    /** O texto que entra nos índices de busca: o título pesa porque é o que liga a pergunta ao trecho. */
    public String searchText() {
        return title + ". " + body;
    }
}
