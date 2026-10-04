package br.com.gilvanborges.javai.ai.indexer.repo;

import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Usa um repositório Git de verdade, criado no teste, e uma API do GitHub falsa (servidor local). Prova o que a IA
 * pode e o que nunca pode ler de um repositório.
 */
class GitHubRepoSourceTest {

    @TempDir
    Path work;

    private HttpServer api;
    private String metadataJson;
    private int metadataStatus;

    @BeforeEach
    void startApi() throws IOException {
        metadataStatus = 200;
        metadataJson = meta(false, false, 100, "main");
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/repos/", exchange -> {
            byte[] body = metadataJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(metadataStatus, metadataStatus == 404 ? -1 : body.length);
            if (metadataStatus != 404) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
    }

    @AfterEach
    void stopApi() {
        api.stop(0);
    }

    @Test
    void le_so_o_que_e_permitido_e_nunca_configuracao_segredo_ou_lixo_de_build() throws Exception {
        createRepo("dono", "projeto", Map.of(
                "README.md", "# Projeto\nMicrosserviço de tickets.",
                "pom.xml", "<project><dependencies><dependency><artifactId>spring-boot-starter-security</artifactId></dependency></dependencies></project>",
                "src/main/java/a/Service.java", "package a;\nclass Service {}",
                "src/main/resources/application.properties", "spring.datasource.password=senhaDeVerdade123",
                ".env", "OPENAI_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz0123",
                "node_modules/lib/index.ts", "export const x = 1;",
                "target/classes/B.java", "package b;",
                "dados.csv", "a,b,c",
                "src/main/java/a/Config.java", "package a;\nclass Config { String password = \"SenhaCommitada99\"; }"));

        var read = source().read("dono/projeto");

        assertThat(read.wasSkipped()).isFalse();
        assertThat(read.files()).extracting(RepoSource.RepoFile::path)
                .containsExactly("README.md", "REPO_FACTS.md", "pom.xml", "src/main/java/a/Service.java");
        // O que o resumo escreve é exatamente o que o documento de atividade sabe ler (as etiquetas são compartilhadas).
        assertThat(RepoActivity.from(read)).hasValueSatisfying(e -> {
            assertThat(e.fullName()).isEqualTo("dono/projeto");
            assertThat(e.lastCommit()).isAfter(java.time.LocalDate.of(2020, 1, 1));
            assertThat(e.message()).isEqualTo("inicial");
        });
        // O resumo do repositório responde "quando foi o último commit?", que o README não diz.
        assertThat(read.files()).filteredOn(f -> f.path().equals("REPO_FACTS.md")).singleElement()
                .satisfies(f -> assertThat(f.content())
                        .contains("Latest commit").contains("dono/projeto").containsPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        // Nada do que ficou de fora aparece no conteúdo lido.
        String everything = read.files().stream().map(RepoSource.RepoFile::content).collect(Collectors.joining("\n"));
        assertThat(everything).doesNotContain("senhaDeVerdade123", "sk-proj-", "SenhaCommitada99");
        // O arquivo com segredo vira um aviso com o caminho e a regra, e o valor não vai junto.
        assertThat(read.notes()).singleElement().satisfies(note -> {
            assertThat(note).contains("src/main/java/a/Config.java").contains("senha-literal");
            assertThat(note).doesNotContain("SenhaCommitada99");
        });
        assertThat(read.commitSha()).matches("[0-9a-f]{40}");
    }

    @Test
    void fork_e_pulado_sem_clonar() {
        metadataJson = meta(false, true, 100, "main");

        var read = source().read("dono/projeto");

        assertThat(read.wasSkipped()).isTrue();
        assertThat(read.skippedReason()).contains("fork");
        assertThat(read.files()).isEmpty();
    }

    @Test
    void repositorio_privado_e_pulado() {
        metadataJson = meta(true, false, 100, "main");

        assertThat(source().read("dono/projeto").skippedReason()).contains("privado");
    }

    @Test
    void repositorio_grande_demais_e_pulado_com_o_motivo() {
        metadataJson = meta(false, false, 61_557, "main"); // o webPonto: 60 MB, com o node_modules no histórico

        var read = source().read("dono/projeto");

        assertThat(read.wasSkipped()).isTrue();
        assertThat(read.skippedReason()).contains("grande demais").contains("61557");
    }

    @Test
    void repositorio_inexistente_ou_privado_para_quem_nao_tem_credencial_e_pulado() {
        metadataStatus = 404;

        assertThat(source().read("dono/projeto").skippedReason()).contains("não encontrado");
    }

    @Test
    void nome_invalido_nunca_chega_na_rede() {
        for (String name : new String[] {"../../etc", "dono", "dono/pro jeto", "dono/proj;rm -rf", "a/b/c", ""}) {
            assertThat(source().read(name).skippedReason()).as(name).contains("inválido");
        }
    }

    @Test
    void erro_da_api_vira_repositorio_pulado_e_nao_derruba_a_execucao() {
        metadataStatus = 500;
        metadataJson = "{}";

        var read = source().read("dono/projeto");

        assertThat(read.wasSkipped()).isTrue();
        assertThat(read.skippedReason()).contains("falha ao ler");
    }

    @Test
    void link_simbolico_nunca_e_seguido() throws Exception {
        Path outside = Files.writeString(work.resolve("fora.md"), "CONTEUDO DE FORA DO REPOSITORIO");
        Path repoDir = work.resolve("origem").resolve("dono").resolve("projeto.git-src");
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Ok\nTexto normal.");
        boolean symlinkCreated;
        try {
            Files.createSymbolicLink(repoDir.resolve("link.md"), outside);
            symlinkCreated = true;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            symlinkCreated = false; // Windows sem privilégio: o teste de link só vale onde dá para criar o link
        }
        commitAndExpose("dono", "projeto", repoDir);

        var read = source().read("dono/projeto");

        String everything = read.files().stream().map(RepoSource.RepoFile::content).collect(Collectors.joining("\n"));
        assertThat(everything).doesNotContain("CONTEUDO DE FORA");
        assertThat(symlinkCreated || read.files().size() == 1).isTrue();
    }

    @Test
    void repositorio_vazio_e_pulado_e_nao_derruba_a_execucao() throws Exception {
        Path bare = work.resolve("origem").resolve("dono").resolve("vazio.git");
        Files.createDirectories(bare);
        try (Git ignored = Git.init().setDirectory(bare.toFile()).setBare(true).setInitialBranch("main").call()) {
            assertThat(bare).exists();
        }

        var read = source().read("dono/vazio");

        assertThat(read.wasSkipped()).isTrue();
        assertThat(read.files()).isEmpty();
    }

    @Test
    void dossie_traz_os_commits_recentes_os_topicos_e_a_branch() throws Exception {
        metadataJson = "{\"private\":false,\"fork\":false,\"size\":100,\"default_branch\":\"main\",\"language\":\"Java\","
                + "\"description\":\"Financas da familia\",\"topics\":[\"java\",\"kafka\"],\"license\":{\"spdx_id\":\"MIT\"}}";
        createRepoWithCommits("dono", "historia", List.of("primeiro commit", "segundo commit", "terceiro commit"));

        var read = source().read("dono/historia");

        String facts = read.files().stream().filter(f -> f.path().equals("REPO_FACTS.md")).findFirst().orElseThrow().content();
        assertThat(facts).contains("## Commits recentes / Recent commits")
                .contains("terceiro commit", "segundo commit", "primeiro commit")
                .contains("Tópicos / Topics: java, kafka")
                .contains("Branch principal / Default branch: main")
                .contains("Licença / License: MIT");
        // O mais recente vem primeiro.
        assertThat(facts.indexOf("terceiro commit")).isLessThan(facts.indexOf("primeiro commit"));
        // O último commit continua legível pelo documento de atividade.
        assertThat(RepoActivity.from(read)).hasValueSatisfying(e -> assertThat(e.message()).isEqualTo("terceiro commit"));
    }

    @Test
    void mensagem_de_commit_com_segredo_nao_derruba_o_dossie_e_nao_vaza() throws Exception {
        createRepoWithCommits("dono", "vazou", List.of("inicial", "chore: String password = \"SenhaCommitada99\";"));

        var read = source().read("dono/vazou");

        String facts = read.files().stream().filter(f -> f.path().equals("REPO_FACTS.md")).findFirst().orElseThrow().content();
        assertThat(facts).doesNotContain("SenhaCommitada99").doesNotContain("password");
        assertThat(facts).contains("## Commits recentes / Recent commits");
        assertThat(facts.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(2);
    }

    @Test
    void mapa_de_commits_vai_ate_150_e_o_dossie_continua_so_com_os_10_mais_recentes() throws Exception {
        var messages = new java.util.ArrayList<String>();
        for (int i = 1; i <= 160; i++) {
            messages.add("commit numero " + i);
        }
        createRepoWithCommits("dono", "longo", messages);

        var read = source().read("dono/longo");

        assertThat(read.commits()).hasSize(GitHubRepoSource.COMMIT_MAP_DEPTH);
        // Do mais novo para o mais antigo, com o hash curto de 7 caracteres e a primeira linha da mensagem.
        assertThat(read.commits().getFirst().subject()).isEqualTo("commit numero 160");
        assertThat(read.commits().getLast().subject()).isEqualTo("commit numero 11");
        assertThat(read.commits()).allSatisfy(c -> assertThat(c.sha()).matches("[0-9a-f]{7}"));
        assertThat(read.commits().getFirst().at()).isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        // O dossiê não mudou: continua com COMMIT_HISTORY linhas de commit e o mais recente primeiro.
        String facts = read.files().stream().filter(f -> f.path().equals("REPO_FACTS.md")).findFirst().orElseThrow().content();
        assertThat(facts.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(GitHubRepoSource.COMMIT_HISTORY);
        assertThat(facts).contains("commit numero 160", "commit numero 151").doesNotContain("commit numero 150");
        assertThat(RepoActivity.from(read)).hasValueSatisfying(e -> assertThat(e.message()).isEqualTo("commit numero 160"));
    }

    @Test
    void assunto_do_mapa_e_a_primeira_linha_com_no_maximo_100_caracteres_e_sem_autor() throws Exception {
        String longo = "feat: " + "x".repeat(200);
        createRepoWithCommits("dono", "assunto", List.of("inicial\n\ncorpo que nao entra", longo));

        var read = source().read("dono/assunto");

        assertThat(read.commits()).extracting(RepoSource.CommitRef::subject).hasSize(2);
        assertThat(read.commits().get(0).subject()).hasSize(100).startsWith("feat: xxx").endsWith("…");
        assertThat(read.commits().get(1).subject()).isEqualTo("inicial");
        // Nem o nome nem o e-mail do autor (t, t@example.com) aparecem em nenhum campo do commit.
        assertThat(read.commits().toString()).doesNotContain("example.com");
    }

    @Test
    void mensagem_com_segredo_em_qualquer_commit_do_mapa_omite_todas_as_mensagens_do_repositorio() throws Exception {
        var messages = new java.util.ArrayList<String>();
        messages.add("chore: String password = \"SenhaCommitada99\";");
        for (int i = 1; i <= 30; i++) {
            messages.add("limpo " + i); // o commit com segredo fica fora dos 10 do dossiê, mas dentro dos 150 do mapa
        }
        createRepoWithCommits("dono", "vazou-fundo", messages);

        var read = source().read("dono/vazou-fundo");

        assertThat(read.commits()).hasSize(31);
        assertThat(read.commits()).allSatisfy(c -> assertThat(c.subject()).isEqualTo(RepoSource.CommitRef.OMITTED));
        assertThat(read.commits().toString()).doesNotContain("SenhaCommitada99", "password");
    }

    @Test
    void mensagem_com_segredo_no_dossie_omite_as_mensagens_do_mapa() throws Exception {
        createRepoWithCommits("dono", "vazou-2", List.of("inicial", "chore: String password = \"SenhaCommitada99\";"));

        var read = source().read("dono/vazou-2");

        assertThat(read.commits()).hasSize(2);
        assertThat(read.commits()).allSatisfy(c -> assertThat(c.subject()).isEqualTo("(mensagem omitida)"));
    }

    @Test
    void repositorio_privado_fork_vazio_ou_com_erro_nao_gera_commit_no_mapa() throws Exception {
        createRepoWithCommits("dono", "privado", List.of("segredo de negocio"));
        metadataJson = meta(true, false, 100, "main");
        assertThat(source().read("dono/privado").commits()).isEmpty();

        metadataJson = meta(false, true, 100, "main");
        assertThat(source().read("dono/privado").commits()).isEmpty();

        metadataStatus = 404;
        assertThat(source().read("dono/privado").commits()).isEmpty();

        metadataStatus = 500;
        metadataJson = "{}";
        assertThat(source().read("dono/privado").commits()).isEmpty();

        Path bare = work.resolve("origem").resolve("dono").resolve("vazio.git");
        Files.createDirectories(bare);
        try (Git ignored = Git.init().setDirectory(bare.toFile()).setBare(true).setInitialBranch("main").call()) {
            assertThat(bare).exists();
        }
        metadataStatus = 200;
        metadataJson = meta(false, false, 100, "main");
        assertThat(source().read("dono/vazio").commits()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private GitHubRepoSource source() {
        return new GitHubRepoSource(new IndexerProperties(
                "http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, work.resolve("model").toString(), 1,
                "http://127.0.0.1:" + api.getAddress().getPort(), work.resolve("origem").toUri().toString(), Duration.ZERO));
    }

    /** Cria um repositório com os arquivos dados e o expõe como {@code <origem>/dono/projeto.git}, para o clone. */
    private void createRepo(String owner, String name, Map<String, String> files) throws Exception {
        Path src = work.resolve("origem").resolve(owner).resolve(name + ".git-src");
        for (var entry : files.entrySet()) {
            Path file = src.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        commitAndExpose(owner, name, src);
    }

    /** Cria um repositório com um commit por mensagem (o último é o mais recente) e o expõe como {@code <origem>/dono/nome.git}. */
    private void createRepoWithCommits(String owner, String name, List<String> messages) throws Exception {
        Path src = work.resolve("origem").resolve(owner).resolve(name + ".git-src");
        Files.createDirectories(src);
        try (Git git = Git.init().setDirectory(src.toFile()).setInitialBranch("main").call()) {
            int i = 0;
            for (String message : messages) {
                Files.writeString(src.resolve("arquivo" + i++ + ".txt"), message);
                git.add().addFilepattern(".").call();
                git.commit().setMessage(message).setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").setSign(false).call();
            }
        }
        Path bare = work.resolve("origem").resolve(owner).resolve(name + ".git");
        try (Git ignored = Git.cloneRepository().setURI(src.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call()) {
            assertThat(bare).exists();
        }
    }

    private void commitAndExpose(String owner, String name, Path src) throws Exception {
        try (Git git = Git.init().setDirectory(src.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("inicial").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").setSign(false).call();
        }
        Path bare = work.resolve("origem").resolve(owner).resolve(name + ".git");
        try (Git ignored = Git.cloneRepository().setURI(src.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call()) {
            assertThat(bare).exists();
        }
    }

    private static String meta(boolean isPrivate, boolean fork, long sizeKb, String branch) {
        return "{\"private\":" + isPrivate + ",\"fork\":" + fork + ",\"size\":" + sizeKb + ",\"default_branch\":\"" + branch + "\"}";
    }
}
