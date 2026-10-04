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
