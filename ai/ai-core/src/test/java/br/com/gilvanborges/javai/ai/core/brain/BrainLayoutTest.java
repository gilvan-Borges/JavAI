package br.com.gilvanborges.javai.ai.core.brain;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrainLayoutTest {

    private static Chunk chunk(String id, SourceKind kind, String ref, String lang, String title) {
        return new Chunk(id, kind, ref, lang, title, "corpo " + id, "https://github.com/dono/x");
    }

    private static double distance(BrainLayout.Node a, BrainLayout.Node b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    /** Dois assuntos bem separados no espaço dos vetores (eixos 0 e 1), com um pouco de ruído. */
    private record Fixture(List<Chunk> chunks, List<float[]> vectors) {
        static Fixture twoTopics() {
            var chunks = new ArrayList<Chunk>();
            var vectors = new ArrayList<float[]>();
            for (int i = 0; i < 4; i++) {
                chunks.add(chunk("a" + i, SourceKind.REPO_README, "repo_readme:dono/alfa:README.md:s" + i, "pt", "Alfa " + i));
                vectors.add(new float[]{1f, 0.02f * i, 0f, 0.01f * i});
                chunks.add(chunk("b" + i, SourceKind.REPO_README, "repo_readme:dono/beta:README.md:s" + i, "pt", "Beta " + i));
                vectors.add(new float[]{0f, 1f, 0.02f * i, -0.01f * i});
            }
            return new Fixture(chunks, vectors);
        }
    }

    @Test
    void pontos_parecidos_ficam_perto_e_assuntos_diferentes_ficam_longe() {
        var f = Fixture.twoTopics();

        var nodes = BrainLayout.of(f.chunks(), f.vectors());

        var byId = new java.util.HashMap<String, BrainLayout.Node>();
        nodes.forEach(n -> byId.put(n.id(), n));
        var a0 = byId.get("repo_readme:dono/alfa:README.md:s0");
        var a3 = byId.get("repo_readme:dono/alfa:README.md:s3");
        var b0 = byId.get("repo_readme:dono/beta:README.md:s0");
        assertThat(distance(a0, a3)).isLessThan(distance(a0, b0) / 3);
    }

    @Test
    void todas_as_posicoes_ficam_dentro_do_desenho() {
        var f = Fixture.twoTopics();

        var nodes = BrainLayout.of(f.chunks(), f.vectors());

        assertThat(nodes).hasSize(8).allSatisfy(n -> {
            assertThat(n.x()).isBetween(0.0, 1.0);
            assertThat(n.y()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void o_resultado_e_sempre_o_mesmo_para_o_mesmo_indice() {
        var f = Fixture.twoTopics();

        assertThat(BrainLayout.positions(BrainLayout.of(f.chunks(), f.vectors())))
                .usingRecursiveComparison().isEqualTo(BrainLayout.positions(BrainLayout.of(f.chunks(), f.vectors())));
    }

    @Test
    void pt_e_en_da_mesma_origem_viram_um_ponto_so_com_o_titulo_em_portugues_sem_o_sufixo_de_parte() {
        var chunks = List.of(
                chunk("en1", SourceKind.PROJECT, "project:controlei", "en", "Controlei project (1/2)"),
                chunk("pt1", SourceKind.PROJECT, "project:controlei", "pt", "Projeto Controlei (1/2)"),
                chunk("pt2", SourceKind.PROJECT, "project:controlei", "pt", "Projeto Controlei (2/2)"));
        var vectors = List.of(new float[]{1f, 0f}, new float[]{0.9f, 0.1f}, new float[]{0.8f, 0.2f});

        var nodes = BrainLayout.of(chunks, vectors);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).title()).isEqualTo("Projeto Controlei");
        assertThat(nodes.get(0).group()).isEqualTo("project:controlei");
    }

    @Test
    void sem_vetores_o_mapa_continua_existindo_e_e_deterministico() {
        var f = Fixture.twoTopics();

        var first = BrainLayout.of(f.chunks(), null);
        var second = BrainLayout.of(f.chunks(), null);

        assertThat(first).hasSize(8).allSatisfy(n -> {
            assertThat(n.x()).isBetween(0.0, 1.0);
            assertThat(n.y()).isBetween(0.0, 1.0);
        });
        assertThat(first).isEqualTo(second);
    }

    @Test
    void sem_vetores_cada_grupo_ocupa_a_sua_fatia() {
        var f = Fixture.twoTopics();

        var nodes = BrainLayout.of(f.chunks(), null);

        // Os dois repositórios ficam em lados opostos do centro: os centros dos grupos estão longe um do outro.
        double ax = 0, ay = 0, bx = 0, by = 0;
        for (var n : nodes) {
            if (n.group().equals("repo:dono/alfa")) {
                ax += n.x() / 4;
                ay += n.y() / 4;
            } else {
                bx += n.x() / 4;
                by += n.y() / 4;
            }
        }
        assertThat(Math.hypot(ax - bx, ay - by)).isGreaterThan(0.15);
    }

    @Test
    void vetores_iguais_nao_quebram_e_caem_no_plano_b() {
        var chunks = List.of(
                chunk("1", SourceKind.PROFILE, "profile", "pt", "Perfil"),
                chunk("2", SourceKind.FAQ, "faq:1", "pt", "FAQ"));
        var vectors = List.of(new float[]{0.5f, 0.5f}, new float[]{0.5f, 0.5f});

        var nodes = BrainLayout.of(chunks, vectors);

        assertThat(nodes).hasSize(2).allSatisfy(n -> assertThat(n.x()).isBetween(0.0, 1.0));
    }

    @Test
    void lista_vazia_e_um_ponto_so() {
        assertThat(BrainLayout.of(List.of(), null)).isEmpty();
        var one = BrainLayout.of(List.of(chunk("1", SourceKind.PROFILE, "profile", "pt", "Perfil")), List.of(new float[]{1f, 2f}));
        assertThat(one).hasSize(1);
        assertThat(one.get(0).x()).isEqualTo(0.5);
    }

    @Test
    void exige_um_vetor_por_trecho() {
        assertThatThrownBy(() -> BrainLayout.of(List.of(chunk("1", SourceKind.PROFILE, "profile", "pt", "Perfil")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agrupa_por_repositorio_projeto_github_ou_tipo() {
        assertThat(BrainLayout.groupOf("repo_readme:dono/controlei:README.md:Arquitetura", SourceKind.REPO_README)).isEqualTo("repo:dono/controlei");
        assertThat(BrainLayout.groupOf("repo_file:dono/controlei:src/A.java", SourceKind.REPO_FILE)).isEqualTo("repo:dono/controlei");
        assertThat(BrainLayout.groupOf("project:javai:arquitetura", SourceKind.PROJECT)).isEqualTo("project:javai");
        assertThat(BrainLayout.groupOf("github-activity:dono", SourceKind.GITHUB_PROFILE)).isEqualTo("github");
        assertThat(BrainLayout.groupOf("profile", SourceKind.PROFILE)).isEqualTo(SourceKind.PROFILE.db());
        assertThat(BrainLayout.groupLabelOf("repo_readme:dono/controlei:README.md:x", SourceKind.REPO_README)).isEqualTo("controlei");
    }
}
