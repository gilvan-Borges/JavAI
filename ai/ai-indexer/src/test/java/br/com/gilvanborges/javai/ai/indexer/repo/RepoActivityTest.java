package br.com.gilvanborges.javai.ai.indexer.repo;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoActivityTest {

    private static String facts(String date, String message) {
        return "# Informações do repositório / Repository facts\n\n"
                + "Repositório / Repository: x/y\n"
                + RepoActivity.COMMIT_LABEL + date + " (UTC), commit abc1234\n"
                + RepoActivity.MESSAGE_LABEL + message + "\n"
                + "Linguagem principal / Main language: Java\n";
    }

    private static RepoSource.RepoRead read(String fullName, String date, String message) {
        return new RepoSource.RepoRead(fullName, "a".repeat(40),
                List.of(new RepoSource.RepoFile(RepoActivity.FACTS_FILE, facts(date, message))), null, List.of());
    }

    @Test
    void le_a_data_e_a_mensagem_do_resumo_do_repositorio() {
        var entry = RepoActivity.from(read("gilvan-Borges/controlei", "2026-10-04", "feat: assistente")).orElseThrow();

        assertThat(entry.lastCommit()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(entry.message()).isEqualTo("feat: assistente");
        assertThat(entry.shortName()).isEqualTo("controlei");
    }

    @Test
    void repositorio_pulado_ou_sem_resumo_nao_entra() {
        assertThat(RepoActivity.from(RepoSource.RepoRead.skipped("a/b", "privado"))).isEmpty();
        var semResumo = new RepoSource.RepoRead("a/b", "a".repeat(40),
                List.of(new RepoSource.RepoFile("README.md", "# x")), null, List.of());
        assertThat(RepoActivity.from(semResumo)).isEmpty();
    }

    @Test
    void o_commit_mais_recente_de_todos_vem_na_primeira_frase_nos_dois_idiomas() {
        var entries = List.of(
                RepoActivity.from(read("gilvan-Borges/formacao-coti", "2026-10-01", "Agrupa as 4 APIs")).orElseThrow(),
                RepoActivity.from(read("gilvan-Borges/controlei", "2026-10-04", "docs: README")).orElseThrow(),
                RepoActivity.from(read("gilvan-Borges/JavAI", "2026-10-03", "fix: limiters")).orElseThrow());

        var chunks = RepoActivity.chunks(entries);

        var pt = chunks.stream().filter(c -> c.lang().equals("pt")).findFirst().orElseThrow();
        var en = chunks.stream().filter(c -> c.lang().equals("en")).findFirst().orElseThrow();
        assertThat(pt.body()).startsWith("O último commit de Gilvan Borges").contains("repositório controlei em 2026-10-04");
        assertThat(en.body()).startsWith("Gilvan Borges' latest commit").contains("repository controlei on 2026-10-04");
        // A lista vai do mais recente para o mais antigo.
        assertThat(pt.body().indexOf("- controlei")).isLessThan(pt.body().indexOf("- JavAI"));
        assertThat(pt.body().indexOf("- JavAI")).isLessThan(pt.body().indexOf("- formacao-coti"));
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(SourceKind.GITHUB_PROFILE);
            assertThat(c.url()).isEqualTo("https://github.com/gilvan-Borges?tab=repositories&sort=updated");
        });
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
    }

    @Test
    void sem_repositorios_nao_gera_trecho() {
        assertThat(RepoActivity.chunks(List.of())).isEmpty();
    }

    @Test
    void mensagem_longa_e_cortada_e_o_numero_de_repositorios_e_limitado() {
        var entries = new ArrayList<RepoActivity.Entry>();
        for (int i = 0; i < 30; i++) {
            entries.add(RepoActivity.from(read("o/repo" + i, "2026-09-" + String.format("%02d", i % 28 + 1), "m".repeat(300))).orElseThrow());
        }

        var chunks = RepoActivity.chunks(entries);

        String all = String.join("\n", chunks.stream().filter(c -> c.lang().equals("pt")).map(Chunk::body).toList());
        assertThat(all.lines().filter(l -> l.startsWith("- "))).hasSize(RepoActivity.MAX_ENTRIES);
        assertThat(all).doesNotContain("m".repeat(RepoActivity.MAX_MESSAGE + 1));
        assertThat(chunks).allSatisfy(c -> assertThat(c.body().length()).isLessThanOrEqualTo(1300));
    }
}
