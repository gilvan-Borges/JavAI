package br.com.gilvanborges.javai.core.content;

import java.time.LocalDate;
import java.util.List;

/**
 * O conteúdo que está publicado e é público, já sem rascunhos.
 *
 * <p>Nenhum destes tipos tem campo para notas privadas, status ou visibilidade. O que não existe aqui
 * não tem como chegar ao snapshot do portfólio nem, na Fase 2, ao índice da IA.
 */
public record PublishedContent(
        Profile profile,
        List<Experience> experiences,
        List<Project> projects,
        List<Skill> skills,
        List<Education> education,
        List<Certification> certifications,
        List<Faq> faq) {

    public PublishedContent {
        experiences = List.copyOf(experiences);
        projects = List.copyOf(projects);
        skills = List.copyOf(skills);
        education = List.copyOf(education);
        certifications = List.copyOf(certifications);
        faq = List.copyOf(faq);
    }

    public record Profile(
            String fullName,
            Localized headline,
            Localized summary,
            String location,
            List<Link> links,
            Localized availability) {

        public Profile {
            links = List.copyOf(links);
        }
    }

    public record Experience(
            String company,
            Localized role,
            LocalDate startedOn,
            LocalDate endedOn,
            Localized description,
            LocalizedList highlights,
            List<String> stack) {

        public Experience {
            stack = List.copyOf(stack);
        }
    }

    public record Project(
            String slug,
            String name,
            Localized summary,
            Localized problem,
            Localized solution,
            Localized results,
            List<String> stack,
            String repository,
            String url,
            boolean featured) {

        public Project {
            stack = List.copyOf(stack);
        }
    }

    public record Skill(String name, String category, boolean declared, Localized notes) {
    }

    public record Education(
            String institution, Localized course, LocalDate startedOn, LocalDate endedOn, String completion) {
    }

    public record Certification(String name, String issuer, LocalDate issuedOn, String url) {
    }

    public record Faq(Localized question, Localized answer) {
    }
}
