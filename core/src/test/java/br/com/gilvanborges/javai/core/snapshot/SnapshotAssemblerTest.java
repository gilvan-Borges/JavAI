package br.com.gilvanborges.javai.core.snapshot;

import br.com.gilvanborges.javai.core.Fixtures;
import br.com.gilvanborges.javai.core.content.Lang;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");

    @Test
    void escolhe_os_textos_do_idioma_pedido() {
        var pt = SnapshotAssembler.assemble(7, NOW, Lang.PT, Fixtures.content());
        var en = SnapshotAssembler.assemble(7, NOW, Lang.EN, Fixtures.content());

        assertThat(pt.lang()).isEqualTo("pt");
        assertThat(pt.profile().headline()).isEqualTo("Engenheiro de Software");
        assertThat(pt.experiences().getFirst().highlights()).containsExactly("Deploy em produção");
        assertThat(pt.faq().getFirst().answer()).isEqualTo("Sim.");

        assertThat(en.lang()).isEqualTo("en");
        assertThat(en.profile().headline()).isEqualTo("Software Engineer");
        assertThat(en.experiences().getFirst().role()).isEqualTo("Developer");
        assertThat(en.faq().getFirst().question()).isEqualTo("Open to remote?");
    }

    @Test
    void campos_sem_idioma_ficam_iguais_nos_dois() {
        var pt = SnapshotAssembler.assemble(7, NOW, Lang.PT, Fixtures.content());
        var en = SnapshotAssembler.assemble(7, NOW, Lang.EN, Fixtures.content());

        assertThat(pt.profile().fullName()).isEqualTo(en.profile().fullName());
        assertThat(pt.projects().getFirst().stack()).isEqualTo(en.projects().getFirst().stack());
        assertThat(pt.indexVersion()).isEqualTo(7);
        assertThat(pt.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void habilidades_saem_sem_evidencia_na_fase_1() {
        var pt = SnapshotAssembler.assemble(7, NOW, Lang.PT, Fixtures.content());

        assertThat(pt.skills()).singleElement().satisfies(s -> {
            assertThat(s.declared()).isTrue();
            assertThat(s.evidence()).isEmpty();
        });
    }
}
