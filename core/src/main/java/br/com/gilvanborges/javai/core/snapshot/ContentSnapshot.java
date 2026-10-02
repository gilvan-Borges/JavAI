package br.com.gilvanborges.javai.core.snapshot;

import br.com.gilvanborges.javai.core.content.Link;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** O conteúdo publicado num idioma, no formato que o portfólio recebe no build (spec 03). */
public record ContentSnapshot(
        long indexVersion,
        Instant generatedAt,
        String lang,
        Profile profile,
        List<Experience> experiences,
        List<Project> projects,
        List<Skill> skills,
        List<Education> education,
        List<Certification> certifications,
        List<Faq> faq) {

    public record Profile(
            String fullName, String headline, String summary, String location, List<Link> links, String availability) {
    }

    public record Experience(
            String company,
            String role,
            LocalDate startedOn,
            LocalDate endedOn,
            String description,
            List<String> highlights,
            List<String> stack) {
    }

    public record Project(
            String slug,
            String name,
            String summary,
            String problem,
            String solution,
            String results,
            List<String> stack,
            String repository,
            String url,
            boolean featured) {
    }

    /** Na Fase 1 a lista de evidências vem vazia; o indexador da Fase 2 a preenche. */
    public record Skill(String name, String category, boolean declared, String notes, List<Evidence> evidence) {
    }

    public record Evidence(String repository, String path, Integer line, String url) {
    }

    public record Education(
            String institution, String course, LocalDate startedOn, LocalDate endedOn, String completion) {
    }

    public record Certification(String name, String issuer, LocalDate issuedOn, String url) {
    }

    public record Faq(String question, String answer) {
    }
}
