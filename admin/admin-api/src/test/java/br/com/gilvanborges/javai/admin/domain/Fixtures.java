package br.com.gilvanborges.javai.admin.domain;

import br.com.gilvanborges.javai.admin.domain.content.Link;
import br.com.gilvanborges.javai.admin.domain.content.Localized;
import br.com.gilvanborges.javai.admin.domain.content.LocalizedList;
import br.com.gilvanborges.javai.admin.domain.content.PublishedContent;

import java.time.LocalDate;
import java.util.List;

public final class Fixtures {

    private Fixtures() {
    }

    public static PublishedContent content() {
        return new PublishedContent(
                profile(),
                List.of(new PublishedContent.Experience(
                        "Vibetex",
                        new Localized("Desenvolvedor", "Developer"),
                        LocalDate.of(2025, 1, 1),
                        null,
                        new Localized("Sistemas de ponta a ponta.", "End-to-end systems."),
                        new LocalizedList(List.of("Deploy em produção"), List.of("Production deploys")),
                        List.of("Java", "Spring Boot"))),
                List.of(new PublishedContent.Project(
                        "api-suporte",
                        "apiSuporte",
                        new Localized("Microsserviço de tickets.", "Ticketing microservice."),
                        Localized.EMPTY,
                        Localized.EMPTY,
                        Localized.EMPTY,
                        List.of("Java 21", "RabbitMQ"),
                        "FightConnect2025/apiSuporte",
                        null,
                        true)),
                List.of(new PublishedContent.Skill("RabbitMQ", "mensageria", true, Localized.EMPTY)),
                List.of(),
                List.of(),
                List.of(new PublishedContent.Faq(
                        new Localized("Aceita remoto?", "Open to remote?"), new Localized("Sim.", "Yes."))));
    }

    public static PublishedContent.Profile profile() {
        return new PublishedContent.Profile(
                "Gilvan Borges",
                new Localized("Engenheiro de Software", "Software Engineer"),
                new Localized("Resumo em português.", "Summary in English."),
                "Rio de Janeiro",
                List.of(new Link("GitHub", "https://github.com/gilvan-Borges")),
                Localized.EMPTY);
    }

    public static PublishedContent withoutProfile() {
        var c = content();
        return new PublishedContent(
                null, c.experiences(), c.projects(), c.skills(), c.education(), c.certifications(), c.faq());
    }
}
