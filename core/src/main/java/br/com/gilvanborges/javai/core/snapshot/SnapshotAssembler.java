package br.com.gilvanborges.javai.core.snapshot;

import br.com.gilvanborges.javai.core.content.Lang;
import br.com.gilvanborges.javai.core.content.PublishedContent;

import java.time.Instant;
import java.util.List;

/** Monta o snapshot de um idioma a partir do conteúdo publicado. */
public final class SnapshotAssembler {

    private SnapshotAssembler() {
    }

    public static ContentSnapshot assemble(
            long indexVersion, Instant generatedAt, Lang lang, PublishedContent content) {
        var profile = content.profile();
        return new ContentSnapshot(
                indexVersion,
                generatedAt,
                lang.code(),
                new ContentSnapshot.Profile(
                        profile.fullName(),
                        profile.headline().in(lang),
                        profile.summary().in(lang),
                        profile.location(),
                        profile.links(),
                        profile.availability().in(lang)),
                content.experiences().stream()
                        .map(e -> new ContentSnapshot.Experience(
                                e.company(),
                                e.role().in(lang),
                                e.startedOn(),
                                e.endedOn(),
                                e.description().in(lang),
                                e.highlights().in(lang),
                                e.stack()))
                        .toList(),
                content.projects().stream()
                        .map(p -> new ContentSnapshot.Project(
                                p.slug(),
                                p.name(),
                                p.summary().in(lang),
                                p.problem().in(lang),
                                p.solution().in(lang),
                                p.results().in(lang),
                                p.stack(),
                                p.repository(),
                                p.url(),
                                p.featured()))
                        .toList(),
                content.skills().stream()
                        .map(s -> new ContentSnapshot.Skill(
                                s.name(), s.category(), s.declared(), s.notes().in(lang), List.of()))
                        .toList(),
                content.education().stream()
                        .map(e -> new ContentSnapshot.Education(
                                e.institution(), e.course().in(lang), e.startedOn(), e.endedOn(), e.completion()))
                        .toList(),
                content.certifications().stream()
                        .map(c -> new ContentSnapshot.Certification(c.name(), c.issuer(), c.issuedOn(), c.url()))
                        .toList(),
                content.faq().stream()
                        .map(f -> new ContentSnapshot.Faq(f.question().in(lang), f.answer().in(lang)))
                        .toList());
    }
}
