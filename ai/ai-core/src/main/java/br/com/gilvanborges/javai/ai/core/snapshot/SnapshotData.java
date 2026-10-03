package br.com.gilvanborges.javai.ai.core.snapshot;

import java.util.List;

/**
 * O snapshot público do CRUD, num idioma, como a IA o enxerga (spec 03, "Snapshot do conteúdo"). É um espelho
 * simples e independente: a IA não compartilha código com o CRUD, só o contrato do arquivo.
 *
 * <p>Datas ficam como texto ISO ({@code 2025-01-01}); o ai-core não precisa fazer conta com elas.
 */
public record SnapshotData(
        String lang,
        Profile profile,
        List<Experience> experiences,
        List<Project> projects,
        List<Skill> skills,
        List<Education> education,
        List<Faq> faq,
        List<Repository> repositories) {

    public SnapshotData {
        experiences = List.copyOf(experiences);
        projects = List.copyOf(projects);
        skills = List.copyOf(skills);
        education = List.copyOf(education);
        faq = List.copyOf(faq);
        repositories = List.copyOf(repositories);
    }

    public record Profile(String fullName, String headline, String summary, String location, String availability) {
    }

    public record Experience(
            String company, String role, String startedOn, String endedOn, String description,
            List<String> highlights, List<String> stack) {
    }

    public record Project(
            String slug, String name, String summary, String problem, String solution, String results,
            List<String> stack, String repository, String url) {
    }

    public record Skill(String name, String category, boolean declared, String notes) {
    }

    public record Education(String institution, String course, String startedOn, String endedOn, String completion) {
    }

    public record Faq(String question, String answer) {
    }

    /** Um repositório do GitHub que o dono ligou para a IA ler (campo {@code repositories}, a partir da Fase 2). */
    public record Repository(String fullName, String kind, String note) {
    }
}
