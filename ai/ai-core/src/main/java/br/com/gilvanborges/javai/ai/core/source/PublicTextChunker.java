package br.com.gilvanborges.javai.ai.core.source;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.MarkdownSections;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.chunk.TextSplitter;
import br.com.gilvanborges.javai.ai.core.text.LanguageGuess;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforma o texto de uma fonte pública (uma página do site, o PDF do currículo, o README do perfil do GitHub) em
 * trechos pesquisáveis. O texto NÃO é reescrito por nenhum modelo: cada trecho é um pedaço literal da fonte, com o
 * nome dela no título e o link de origem, para a resposta poder citar de onde veio. O idioma é estimado por trecho.
 */
public final class PublicTextChunker {

    static final int MAX_CHARS = 1200;
    private static final int MIN_CHARS = 20;

    private PublicTextChunker() {
    }

    /**
     * @param kind  {@code SITE}, {@code DOCUMENT} ou {@code GITHUB_PROFILE}
     * @param label como a fonte aparece no título ("Site gilvanborges.com.br", "Currículo (PDF)")
     * @param ref   identificador estável da fonte (a URL, normalmente); entra no {@code sourceRef}
     * @param url   o link que o servidor mostra como fonte (precisa ser https)
     */
    public static List<Chunk> chunk(SourceKind kind, String label, String ref, String url, String text) {
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("A URL da fonte precisa ser https.");
        }
        String body = HtmlText.looksLikeHtml(text) ? HtmlText.toText(text) : text;
        var out = new ArrayList<Chunk>();
        int counter = 0;
        for (var section : MarkdownSections.split(body)) {
            String heading = section.heading().strip();
            String sectionText = section.text().strip();
            if (sectionText.length() < MIN_CHARS) {
                continue;
            }
            String lang = LanguageGuess.of(sectionText);
            String title = heading.isEmpty() ? label : label + ": " + heading;
            String sourceRef = kind.db() + ":" + ref + (heading.isEmpty() ? "" : ":" + heading);
            List<String> parts = TextSplitter.split(sectionText, MAX_CHARS);
            for (int i = 0; i < parts.size(); i++) {
                String suffix = parts.size() > 1 ? " (" + (i + 1) + "/" + parts.size() + ")" : "";
                out.add(new Chunk(lang + ":" + sourceRef + "#" + (counter++), kind, truncate(sourceRef, 390), lang,
                        truncate(title + suffix, 390), parts.get(i), url));
            }
        }
        return List.copyOf(out);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
