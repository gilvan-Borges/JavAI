package br.com.gilvanborges.javai.ai.core.repo;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import br.com.gilvanborges.javai.ai.core.search.VectorOps;
import br.com.gilvanborges.javai.ai.core.text.LanguageGuess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoTest {

    // --- o que a IA pode ler ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "README.md", "docs/arquitetura.md", "pom.xml", "api/pom.xml", "package.json", "Dockerfile", "api/Dockerfile",
            "docker-compose.yml", "infra/docker-compose.prod.yaml", "compose.yaml", ".github/workflows/ci.yml",
            "src/main/java/br/com/a/Service.java", "src/test/java/br/com/a/ServiceTest.java", "app/src/Main.kt",
            "src/app/app.component.ts", "lib/main.dart", "Api/Program.cs", "Api/Api.csproj", "pubspec.yaml",
            "src/main/resources/db/migration/V1__init.sql", "build.gradle.kts"})
    void arquivos_do_projeto_entram(String path) {
        assertThat(RepoFileFilter.allowed(path, 2000)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // onde mora senha, mesmo em repositório público
            "src/main/resources/application.properties", "application-prod.yml", "api/application.yaml", ".env", ".env.local",
            "appsettings.json", "certs/origin.pem", "server.key", "keystore.p12", "secrets.json", "prod.tfvars",
            // lixo de build e dependências
            "node_modules/lodash/index.ts", "target/classes/A.java", "build/out.java", "dist/main.ts", ".angular/cache/a.ts",
            "vendor/lib.java", ".git/config", "bin/Debug/a.cs", "frontend/node_modules/x/README.md",
            // dependências travadas, binários e gerados
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "app.min.js", "logo.png", "relatorio.pdf", "libs/a.jar", "model.onnx",
            // fora da lista permitida
            "dados.csv", "script.sh", "LICENSE", "index.html", "styles.css", "notes.txt"})
    void segredos_lixo_e_o_que_nao_esta_na_lista_ficam_de_fora(String path) {
        assertThat(RepoFileFilter.allowed(path, 2000)).as(path).isFalse();
    }

    @Test
    void tamanho_invalido_ou_grande_demais_e_recusado() {
        assertThat(RepoFileFilter.allowed("README.md", RepoFileFilter.MAX_FILE_BYTES)).isTrue();
        assertThat(RepoFileFilter.allowed("README.md", RepoFileFilter.MAX_FILE_BYTES + 1)).isFalse();
        assertThat(RepoFileFilter.allowed("README.md", 0)).isFalse();
    }

    @Test
    void caminhos_suspeitos_sao_recusados() {
        assertThat(RepoFileFilter.allowed("../../etc/passwd.md", 100)).isFalse();
        assertThat(RepoFileFilter.allowed("/etc/hosts.md", 100)).isFalse();
        assertThat(RepoFileFilter.allowed(null, 100)).isFalse();
        assertThat(RepoFileFilter.allowed("  ", 100)).isFalse();
    }

    // --- segredos -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE...", "-----BEGIN PRIVATE KEY-----", "-----BEGIN OPENSSH PRIVATE KEY-----",
            "aws_key = AKIAIOSFODNN7EXAMPLE", "token ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "OPENAI=sk-proj-abcdefghijklmnopqrstuvwxyz0123", "key sk-or-v1-0123456789abcdef0123456789abcdef",
            "jdbc url postgres://usuario:SenhaSecreta123@db.exemplo.com:5432/app",
            "String password = \"SenhaSuperSecreta1\";", "api_key: 'abcdef1234567890'",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"})
    void detecta_segredo_comum(String content) {
        assertThat(SecretScanner.firstFinding(content)).as(content).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.password=${DB_PASSWORD}", "String password = System.getenv(\"DB_PASSWORD\");",
            "private String password;", "// a senha vem do ambiente", "public class Service { }",
            "https://github.com/gilvan-Borges/JavAI", "password: ${ADMIN_APP_PASSWORD:?defina no .env}", "token = null"})
    void nao_acusa_codigo_normal_nem_referencia_a_variavel_de_ambiente(String content) {
        assertThat(SecretScanner.firstFinding(content)).as(content).isEmpty();
    }

    @Test
    void o_relatorio_traz_so_o_nome_da_regra_nunca_o_valor() {
        String segredo = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";

        var finding = SecretScanner.firstFinding("x = " + segredo);

        assertThat(finding).hasValue("github-token");
        assertThat(finding.get()).doesNotContain(segredo);
    }

    // --- idioma -------------------------------------------------------------------------------------------

    @Test
    void chuta_o_idioma_do_readme() {
        assertThat(LanguageGuess.of("Este projeto serve para gerenciar tickets com SLA e não depende de nada")).isEqualTo("pt");
        assertThat(LanguageGuess.of("This project is for managing tickets and it does not depend on anything")).isEqualTo("en");
        assertThat(LanguageGuess.of("class A { int x; }")).isEqualTo("en"); // sem pista: inglês
    }

    // --- trechos de repositório --------------------------------------------------------------------------

    @Test
    void readme_vira_um_trecho_por_secao_com_link_no_commit() {
        String readme = "# apiSuporte\nMicrosserviço de tickets para o projeto.\n## Como rodar\nUse mvn verify para rodar tudo com Docker.";

        var chunks = RepoChunker.chunk("FightConnect2025/apiSuporte", "abc123", "README.md", readme);

        assertThat(chunks).hasSize(2).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(SourceKind.REPO_README);
            assertThat(c.url()).isEqualTo("https://github.com/FightConnect2025/apiSuporte/blob/abc123/README.md");
            assertThat(c.title()).contains("FightConnect2025/apiSuporte");
        });
        assertThat(chunks.get(1).title()).endsWith("Como rodar");
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
    }

    @Test
    void codigo_vira_trechos_com_o_caminho_no_titulo() {
        String code = ("package a;\n\n// " + "x".repeat(900) + "\nclass A {}\n\n// " + "y".repeat(900) + "\nclass B {}");

        var chunks = RepoChunker.chunk("gilvan-Borges/x", "sha", "src/main/java/a/A.java", code);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(SourceKind.REPO_FILE);
            assertThat(c.title()).contains("src/main/java/a/A.java");
            assertThat(c.body().length()).isLessThanOrEqualTo(1200);
        });
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
    }

    @Test
    void conteudo_de_repositorio_aparece_mesmo_para_quem_pergunta_em_outro_idioma() {
        var code = new Chunk("c1", SourceKind.REPO_FILE, "ref", "en", "Code of repo", "uses RabbitMQ listener", "https://github.com/a/b");
        var ptOnly = new Chunk("c2", SourceKind.SKILL, "ref2", "pt", "Habilidade", "usa RabbitMQ", "https://gilvanborges.com.br");
        var enOnly = new Chunk("c3", SourceKind.SKILL, "ref3", "en", "Skill", "uses RabbitMQ", "https://gilvanborges.com.br");
        var hybrid = new HybridSearcher(List.of(code, ptOnly, enOnly), null, VectorOps.best());

        var hits = hybrid.search("rabbitmq", null, "pt", 5);

        assertThat(hits).extracting(h -> h.chunk().id()).containsExactlyInAnyOrder("c1", "c2"); // o de inglês não-neutro sai
    }
}
