package br.com.gilvanborges.javai.ai.core.repo;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.MarkdownSections;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.chunk.TextSplitter;
import br.com.gilvanborges.javai.ai.core.text.LanguageGuess;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transforma um arquivo já aprovado de um repositório em trechos. README vira trechos por seção; código e manifestos
 * viram trechos por blocos, cada um com o repositório e o caminho no título, para a busca e a citação saberem de onde veio.
 * O link da fonte aponta para o arquivo no commit lido, e não para o branch, que muda.
 */
public final class RepoChunker {

    static final int MAX_CHARS = 1200;

    private RepoChunker() {
    }

    public static List<Chunk> chunk(String repository, String ref, String path, String content) {
        String url = "https://github.com/" + repository + "/blob/" + ref + "/" + path;
        var out = new ArrayList<Chunk>();
        if (path.toLowerCase(Locale.ROOT).endsWith(".md")) {
            String lang = LanguageGuess.of(content);
            String base = "README do repositório " + repository + " de Gilvan Borges / README of Gilvan Borges' repository " + repository;
            for (var section : MarkdownSections.split(content)) {
                String title = section.heading().isEmpty() ? base : base + ": " + section.heading();
                String sourceRef = SourceKind.REPO_README.db() + ":" + repository + ":" + path + ":" + section.heading();
                add(out, SourceKind.REPO_README, sourceRef, lang, title, section.text(), url);
            }
        } else {
            String title = "Código do repositório " + repository + " de Gilvan Borges / Code of Gilvan Borges' repository "
                    + repository + ": " + path;
            String sourceRef = SourceKind.REPO_FILE.db() + ":" + repository + ":" + path;
            add(out, SourceKind.REPO_FILE, sourceRef, "en", title, content, url);
        }
        return List.copyOf(out);
    }

    private static void add(List<Chunk> out, SourceKind kind, String sourceRef, String lang, String title, String body, String url) {
        List<String> parts = TextSplitter.split(body, MAX_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            String suffix = parts.size() > 1 ? " (" + (i + 1) + "/" + parts.size() + ")" : "";
            out.add(new Chunk(lang + ":" + sourceRef + "#" + i, kind, sourceRef, lang, title + suffix, parts.get(i), url));
        }
    }
}
