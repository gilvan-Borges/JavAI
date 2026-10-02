package br.com.gilvanborges.javai.core.publish;

import br.com.gilvanborges.javai.core.content.Lang;
import br.com.gilvanborges.javai.core.content.PublishedContent;
import br.com.gilvanborges.javai.core.snapshot.SnapshotAssembler;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caso de uso: publicar o conteúdo.
 *
 * <p>Na Fase 1 a publicação gera só os snapshots, um por idioma. Na Fase 2 o indexador entra aqui, antes
 * da ativação, sem mudar a rota nem o contrato.
 */
public final class PublishContent {

    private final PublishedContentReader reader;
    private final IndexVersionStore store;
    private final PortfolioNotifier notifier;
    private final Clock clock;

    public PublishContent(
            PublishedContentReader reader, IndexVersionStore store, PortfolioNotifier notifier, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
    }

    public PublishOutcome publish() {
        long version = store.createVersion();
        PublishedContent content;
        try {
            content = reader.read();
            if (content.profile() == null) {
                throw new PublishRejectedException("O perfil precisa estar publicado e público antes da primeira publicação.");
            }
            Instant generatedAt = clock.instant();
            for (Lang lang : Lang.values()) {
                store.saveSnapshot(SnapshotAssembler.assemble(version, generatedAt, lang, content));
            }
            store.activate(version);
        } catch (RuntimeException e) {
            store.markFailed(version);
            throw e;
        }
        // O conteúdo já está no ar para a IA e o MCP. Uma falha no aviso não desfaz a ativação.
        boolean notified = notifier.contentPublished(version);
        return new PublishOutcome(version, counts(content), notified);
    }

    private static Map<String, Integer> counts(PublishedContent content) {
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("experiences", content.experiences().size());
        counts.put("projects", content.projects().size());
        counts.put("skills", content.skills().size());
        counts.put("education", content.education().size());
        counts.put("certifications", content.certifications().size());
        counts.put("faq", content.faq().size());
        return counts;
    }
}
