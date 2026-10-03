package br.com.gilvanborges.javai.ai.core.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillDetectorsTest {

    private static final String REPO = "FightConnect2025/apiSuporte";
    private static final String SHA = "abc123";

    private static List<String> skills(List<Evidence> found) {
        return found.stream().map(Evidence::skill).toList();
    }

    @Test
    void pom_prova_as_dependencias_e_aponta_a_linha_exata() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><artifactId>spring-boot-starter-security</artifactId></dependency>
                    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
                  </dependencies>
                </project>""";

        var found = SkillDetectors.detect(REPO, SHA, "pom.xml", pom);

        assertThat(skills(found)).contains("Spring Boot", "Spring Security e OAuth2/OIDC", "Flyway", "PostgreSQL");
        var security = found.stream().filter(e -> e.skill().startsWith("Spring Security")).findFirst().orElseThrow();
        assertThat(security.line()).isEqualTo(4);
        assertThat(security.url()).isEqualTo("https://github.com/" + REPO + "/blob/" + SHA + "/pom.xml#L4");
    }

    @Test
    void flyway_database_postgresql_sozinho_nao_prova_o_banco() {
        String pom = "<dependency><artifactId>flyway-database-postgresql</artifactId></dependency>";

        assertThat(skills(SkillDetectors.detect(REPO, SHA, "pom.xml", pom))).doesNotContain("PostgreSQL");
    }

    @Test
    void habilidade_ausente_no_manifesto_nao_e_inventada() {
        var found = SkillDetectors.detect(REPO, SHA, "pom.xml", "<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>");

        assertThat(skills(found)).doesNotContain("RabbitMQ", "MongoDB", "Redis", "Testcontainers");
    }

    @Test
    void arquivos_de_infraestrutura_sao_detectados_pelo_caminho() {
        assertThat(skills(SkillDetectors.detect(REPO, SHA, "Dockerfile", "FROM eclipse-temurin"))).containsExactly("Docker");
        assertThat(skills(SkillDetectors.detect(REPO, SHA, "infra/docker-compose.yml", "services:"))).containsExactly("Docker");
        assertThat(skills(SkillDetectors.detect(REPO, SHA, ".github/workflows/ci.yml", "on: push"))).containsExactly("GitHub Actions");
        assertThat(skills(SkillDetectors.detect(REPO, SHA, "src/main/resources/db/migration/V1__x.sql", "create table t();")))
                .containsExactly("Flyway");
    }

    @Test
    void teste_com_junit_e_testcontainers() {
        String test = "package a;\nimport org.junit.jupiter.api.Test;\nimport org.testcontainers.junit.jupiter.Container;\nclass T {}";

        var found = SkillDetectors.detect(REPO, SHA, "src/test/java/a/T.java", test);

        assertThat(skills(found)).contains("Java", "JUnit 5 e Mockito", "Testcontainers");
        assertThat(found.stream().filter(e -> e.skill().equals("JUnit 5 e Mockito")).findFirst().orElseThrow().line()).isEqualTo(2);
    }

    @Test
    void classe_comum_nao_prova_teste() {
        var found = SkillDetectors.detect(REPO, SHA, "src/main/java/a/Service.java", "package a;\nclass Service {}");

        assertThat(skills(found)).containsExactly("Java");
    }

    @Test
    void front_end_e_mobile_pelos_manifestos() {
        assertThat(skills(SkillDetectors.detect("u/web", SHA, "package.json", "{\"dependencies\":{\"@angular/core\":\"21\",\"typescript\":\"5\"}}")))
                .contains("Angular", "TypeScript");
        assertThat(skills(SkillDetectors.detect("u/app", SHA, "pubspec.yaml", "dependencies:\n  flutter:\n    sdk: flutter")))
                .containsExactly("Flutter");
    }

    @Test
    void uma_evidencia_por_habilidade_e_arquivo() {
        String pom = "<a>spring-boot-starter</a>\n<b>spring-boot-starter-web</b>\n<c>org.springframework.boot</c>";

        assertThat(skills(SkillDetectors.detect(REPO, SHA, "pom.xml", pom))).containsExactly("Spring Boot");
    }
}
