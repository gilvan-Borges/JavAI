package br.com.gilvanborges.javai.admin.domain.publish;

import br.com.gilvanborges.javai.admin.domain.content.Lang;
import br.com.gilvanborges.javai.admin.domain.content.PublishedContent;
import br.com.gilvanborges.javai.admin.domain.snapshot.SnapshotAssembler;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caso de uso: publicar o conteúdo.
 *
 * <p>Gera um snapshot por idioma, ativa a versão (o que exporta o arquivo público) e avisa o portfólio. O CRUD não
 * sabe que a IA existe: ela lê o mesmo arquivo público, por conta própria (spec 01).
 */
public final class PublishContent {

    private final PublishedContentReader reader;
    private final PublicationStore store;
    private final PortfolioNotifier notifier;
    private final Clock clock;

    public PublishContent(
            PublishedContentReader reader, PublicationStore store, PortfolioNotifier notifier, Clock clock) {
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
        // O snapshot público já está no ar. Uma falha no aviso não desfaz a ativação.
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
