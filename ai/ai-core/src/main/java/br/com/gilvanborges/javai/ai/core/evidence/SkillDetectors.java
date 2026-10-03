package br.com.gilvanborges.javai.ai.core.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Detectores de habilidade por regra (spec 01, "Evidências de habilidades"): olham o nome do arquivo e, quando
 * preciso, o conteúdo. Não há modelo de linguagem aqui, então o resultado é reproduzível e dá para explicar.
 */
public final class SkillDetectors {

    private record Rule(String skill, String detector, Predicate<String> path, List<String> contains) {
    }

    private static final List<Rule> RULES = List.of(
            // Manifestos de build
            build("Spring Boot", "spring-boot-starter", "org.springframework.boot"),
            build("Spring Security e OAuth2/OIDC", "spring-boot-starter-security", "spring-boot-starter-oauth2", "spring-security"),
            build("JPA / Hibernate", "spring-boot-starter-data-jpa", "hibernate-core"),
            build("Flyway", "org.flywaydb", "flyway-core"),
            build("Testcontainers", "org.testcontainers", "testcontainers"),
            build("RabbitMQ", "spring-boot-starter-amqp", "spring-rabbit", "com.rabbitmq"),
            // "postgresql" solto também aparece em flyway-database-postgresql, que não prova o uso do banco.
            build("PostgreSQL", "<groupId>org.postgresql</groupId>", "<artifactId>postgresql</artifactId>", "org.postgresql:postgresql"),
            build("MongoDB", "spring-boot-starter-data-mongodb", "mongodb-driver"),
            build("MySQL", "mysql-connector", "com.mysql"),
            build("Redis", "spring-boot-starter-data-redis", "lettuce-core", "jedis"),
            build("JUnit 5 e Mockito", "junit-jupiter", "mockito"),
            new Rule("Angular", "package.json: @angular/core", name("package.json"), List.of("\"@angular/core\"")),
            new Rule("TypeScript", "package.json: typescript", name("package.json"), List.of("\"typescript\"")),
            new Rule("Flutter", "pubspec.yaml: flutter", name("pubspec.yaml"), List.of("flutter:")),
            new Rule("C# / .NET", "arquivo .csproj", ext(".csproj"), List.of()),
            // Pelo tipo do arquivo
            new Rule("Docker", "Dockerfile", name("Dockerfile"), List.of()),
            new Rule("Docker", "docker-compose", p -> base(p).startsWith("docker-compose") || base(p).startsWith("compose."), List.of()),
            new Rule("GitHub Actions", "workflow do GitHub Actions",
                    p -> p.startsWith(".github/workflows/") && (p.endsWith(".yml") || p.endsWith(".yaml")), List.of()),
            new Rule("Flyway", "migração em db/migration", p -> p.contains("db/migration/") && p.endsWith(".sql"), List.of()),
            // Pelo código-fonte
            new Rule("Java", "código .java", ext(".java"), List.of("package ")),
            new Rule("Kotlin", "código .kt", ext(".kt"), List.of("package ", "fun ")),
            new Rule("TypeScript", "código .ts", ext(".ts"), List.of("import ", "export ")),
            new Rule("Dart", "código .dart", ext(".dart"), List.of("import ")),
            new Rule("JUnit 5 e Mockito", "teste com JUnit 5", p -> p.endsWith(".java") && p.contains("/test/"),
                    List.of("org.junit.jupiter")),
            new Rule("Testcontainers", "teste com Testcontainers", p -> p.endsWith(".java") && p.contains("/test/"),
                    List.of("org.testcontainers")),
            new Rule("Spring Security e OAuth2/OIDC", "classe com Spring Security", ext(".java"),
                    List.of("org.springframework.security", "@EnableWebSecurity")));

    private SkillDetectors() {
    }

    /**
     * @param ref o commit lido, usado para fixar o link na linha exata
     * @return no máximo uma evidência por habilidade e arquivo
     */
    public static List<Evidence> detect(String repository, String ref, String path, String content) {
        Map<String, Evidence> found = new LinkedHashMap<>();
        String[] lines = content.split("\\R", -1);
        for (Rule rule : RULES) {
            if (!rule.path().test(path) || found.containsKey(rule.skill())) {
                continue;
            }
            int line = rule.contains().isEmpty() ? 1 : firstLine(lines, rule.contains());
            if (line > 0) {
                found.put(rule.skill(), new Evidence(rule.skill(), repository, path, line, rule.detector(),
                        "https://github.com/" + repository + "/blob/" + ref + "/" + path + "#L" + line));
            }
        }
        return new ArrayList<>(found.values());
    }

    private static Rule build(String skill, String... needles) {
        return new Rule(skill, "dependência no manifesto de build",
                p -> base(p).equals("pom.xml") || base(p).equals("build.gradle") || base(p).equals("build.gradle.kts"),
                List.of(needles));
    }

    private static int firstLine(String[] lines, List<String> needles) {
        for (int i = 0; i < lines.length; i++) {
            for (String needle : needles) {
                if (lines[i].contains(needle)) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static Predicate<String> name(String file) {
        return p -> base(p).equals(file);
    }

    private static Predicate<String> ext(String suffix) {
        return p -> p.endsWith(suffix);
    }

    private static String base(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
