package br.com.gilvanborges.javai.ai.indexer.build;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.embedding.Embedder;
import br.com.gilvanborges.javai.ai.indexer.IndexerFixtures;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static br.com.gilvanborges.javai.ai.indexer.IndexerFixtures.fakeEmbedder;
import static br.com.gilvanborges.javai.ai.indexer.IndexerFixtures.repo;
import static br.com.gilvanborges.javai.ai.indexer.IndexerFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

class IndexBuilderTest {

    private static final String POM = "<project><dependencies>"
            + "<dependency><artifactId>spring-boot-starter-security</artifactId></dependency>"
            + "<dependency><artifactId>spring-boot-starter-amqp</artifactId></dependency></dependencies></project>";

    private static Map<String, br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData> snapshots() {
        var map = new LinkedHashMap<String, br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData>();
        map.put("pt", snapshot("pt"));
        map.put("en", snapshot("en"));
        return map;
    }

    @Test
    void junta_trechos_do_snapshot_nos_dois_idiomas_e_dos_repositorios() {
        var repo = repo("FightConnect2025/apiSuporte",
                "README.md", "# apiSuporte\nMicrosserviço de tickets.",
                "pom.xml", POM,
                "src/main/java/a/Service.java", "package a;\nclass Service {}");

        var built = new IndexBuilder(300).build(snapshots(), List.of(repo), Optional.empty());

        assertThat(built.chunks()).extracting(Chunk::lang).contains("pt", "en");
        assertThat(built.chunks()).extracting(Chunk::kind).contains(SourceKind.PROFILE, SourceKind.PROJECT, SourceKind.SKILL,
                SourceKind.FAQ, SourceKind.REPO_README, SourceKind.REPO_FILE, SourceKind.REPO_META);
        assertThat(built.chunks()).extracting(Chunk::id).doesNotHaveDuplicates();
        assertThat(built.report()).containsEntry("repositoriosLidos", List.of("FightConnect2025/apiSuporte"));
    }

    @Test
    void gera_o_documento_de_atividade_que_compara_todos_os_repositorios() {
        String older = "Último commit / Latest commit: 2026-10-01 (UTC), commit aaaaaaa\nMensagem do último commit / Latest commit message: velho\n";
        String newer = "Último commit / Latest commit: 2026-10-04 (UTC), commit bbbbbbb\nMensagem do último commit / Latest commit message: novo\n";
        var a = repo("dono/antigo", "README.md", "# a", "REPO_FACTS.md", older);
        var b = repo("dono/recente", "README.md", "# b", "REPO_FACTS.md", newer);

        var built = new IndexBuilder(300).build(snapshots(), List.of(a, b), Optional.empty());

        var activity = built.chunks().stream().filter(c -> c.sourceRef().startsWith("github-activity:")).toList();
        assertThat(activity).extracting(Chunk::lang).contains("pt", "en");
        var pt = activity.stream().filter(c -> c.lang().equals("pt")).findFirst().orElseThrow();
        assertThat(pt.body()).contains("repositório recente em 2026-10-04");
        assertThat(built.report()).containsEntry("trechosDeAtividade", activity.size());
        assertThat(built.chunks()).extracting(Chunk::id).doesNotHaveDuplicates();
    }

    @Test
    void mapa_de_commits_so_tem_repositorio_lido_e_o_relatorio_conta_os_commits() {
        var commits = List.of(
                new RepoSource.CommitRef("bbbbbbb", java.time.Instant.parse("2026-10-03T14:21:00Z"), "fix: dois"),
                new RepoSource.CommitRef("aaaaaaa", java.time.Instant.parse("2026-10-01T10:00:00Z"), "feat: um"));
        var publico = new RepoSource.RepoRead("dono/publico", "b".repeat(40), List.of(), null, List.of(), commits);
        var privado = RepoSource.RepoRead.skipped("dono/privado", "repositório privado");
        var fork = RepoSource.RepoRead.skipped("dono/fork", "repositório é fork");

        var built = new IndexBuilder(300).build(snapshots(), List.of(publico, privado, fork), Optional.empty());

        assertThat(built.commits()).containsOnlyKeys("dono/publico");
        assertThat(built.commits().get("dono/publico")).isEqualTo(commits);
        assertThat(built.report()).containsEntry("commitsNoMapa", 2);
    }

    @Test
    void repositorio_privado_nao_gera_commit_nem_no_relatorio() {
        var privado = RepoSource.RepoRead.skipped("dono/privado", "repositório privado");

        var built = new IndexBuilder(300).build(snapshots(), List.of(privado), Optional.empty());

        assertThat(built.commits()).isEmpty();
        assertThat(built.report()).containsEntry("commitsNoMapa", 0);
    }

    @Test
    void evidencias_vem_dos_arquivos_lidos_sem_repeticao() {
        var repo = repo("FightConnect2025/apiSuporte", "pom.xml", POM, "pom2/pom.xml", POM);

        var built = new IndexBuilder(300).build(snapshots(), List.of(repo), Optional.empty());

        assertThat(built.evidence()).extracting(e -> e.skill()).contains("Spring Security e OAuth2/OIDC", "RabbitMQ", "Spring Boot");
        assertThat(built.evidence()).extracting(e -> e.skill() + "|" + e.path()).doesNotHaveDuplicates();
        assertThat(built.evidence()).allSatisfy(e -> assertThat(e.url()).contains("/blob/" + "a".repeat(40) + "/"));
    }

    @Test
    void repositorio_pulado_fica_no_relatorio_com_o_motivo_e_nao_gera_trecho() {
        var skipped = RepoSource.RepoRead.skipped("gilvan-Borges/webPonto", "repositório grande demais (61557 KB; limite 51200 KB)");

        var built = new IndexBuilder(300).build(snapshots(), List.of(skipped), Optional.empty());

        assertThat(built.report().get("repositoriosPulados").toString()).contains("webPonto").contains("grande demais");
        assertThat(built.chunks()).noneMatch(c -> c.kind() == SourceKind.REPO_FILE || c.kind() == SourceKind.REPO_README);
    }

    @Test
    void teto_de_trechos_por_repositorio_corta_o_codigo_e_mantem_o_readme() {
        var many = new java.util.ArrayList<String>();
        many.add("README.md");
        many.add("# Projeto\nTexto do readme.");
        for (int i = 0; i < 30; i++) {
            many.add("src/main/java/a/Classe" + i + ".java");
            many.add("package a;\nclass Classe" + i + " { }");
        }
        var repo = repo("dono/grande", many.toArray(new String[0]));

        var built = new IndexBuilder(5).build(snapshots(), List.of(repo), Optional.empty());

        var repoChunks = built.chunks().stream().filter(c -> c.kind() == SourceKind.REPO_README || c.kind() == SourceKind.REPO_FILE).toList();
        assertThat(repoChunks).hasSize(5);
        assertThat(repoChunks.getFirst().kind()).isEqualTo(SourceKind.REPO_README); // o README entra primeiro
        assertThat(built.evidence()).extracting(e -> e.skill()).contains("Java"); // a evidência não depende do teto
    }

    @Test
    void com_modelo_gera_um_vetor_por_trecho() {
        Embedder embedder = fakeEmbedder(384);

        var built = new IndexBuilder(300).build(snapshots(), List.of(), Optional.of(embedder));

        assertThat(built.embeddings()).hasSameSizeAs(built.chunks());
        assertThat(built.embeddings()).allSatisfy(v -> assertThat(v).hasSize(384));
        assertThat(built.report()).containsEntry("comEmbeddings", true).containsEntry("modeloDeEmbeddings", "falso");
    }

    @Test
    void sem_modelo_o_indice_sai_so_com_bm25() {
        var built = new IndexBuilder(300).build(snapshots(), List.of(), Optional.empty());

        assertThat(built.embeddings()).isNull();
        assertThat(built.chunks()).isNotEmpty();
        assertThat(built.report()).containsEntry("comEmbeddings", false);
    }

    @Test
    void falha_no_modelo_nao_derruba_a_indexacao() {
        Embedder quebrado = new Embedder() {
            @Override public int dimensions() { return 384; }
            @Override public String modelId() { return "quebrado"; }
            @Override public float[] embedQuery(String text) { throw new IllegalStateException("boom"); }
            @Override public float[] embedPassage(String text) { throw new IllegalStateException("boom"); }
        };

        var built = new IndexBuilder(300).build(snapshots(), List.of(), Optional.of(quebrado));

        assertThat(built.embeddings()).isNull();
        assertThat(built.chunks()).isNotEmpty();
        assertThat(built.report()).containsEntry("falhaNosEmbeddings", "IllegalStateException");
    }

    @Test
    void relatorio_nao_carrega_conteudo_de_arquivo() {
        var repo = repo("dono/p", "src/main/java/a/Segredo.java", "package a;\n// CONTEUDO-UNICO-DO-ARQUIVO\nclass Segredo {}");

        var built = new IndexBuilder(300).build(snapshots(), List.of(repo), Optional.empty());

        assertThat(built.report().toString()).doesNotContain("CONTEUDO-UNICO-DO-ARQUIVO");
    }
}
