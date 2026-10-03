package br.com.gilvanborges.javai.admin.publish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SnapshotFilesTest {

    @TempDir
    Path directory;

    @Test
    void exporta_um_arquivo_por_idioma() throws IOException {
        files().export(List.of(
                new SnapshotFiles.StoredSnapshot("en", "{\"lang\":\"en\"}"),
                new SnapshotFiles.StoredSnapshot("pt", "{\"lang\":\"pt\"}")));

        assertThat(Files.readString(directory.resolve("pt.json"))).isEqualTo("{\"lang\":\"pt\"}");
        assertThat(Files.readString(directory.resolve("en.json"))).isEqualTo("{\"lang\":\"en\"}");
    }

    @Test
    void nova_exportacao_substitui_e_nao_deixa_temporarios() throws IOException {
        files().export(List.of(new SnapshotFiles.StoredSnapshot("pt", "{\"v\":1}")));
        files().export(List.of(new SnapshotFiles.StoredSnapshot("pt", "{\"v\":2}")));

        assertThat(Files.readString(directory.resolve("pt.json"))).isEqualTo("{\"v\":2}");
        try (var listing = Files.list(directory)) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("pt.json");
        }
    }

    @Test
    void idioma_fora_do_enum_nunca_vira_caminho() {
        assertThatIllegalStateException().isThrownBy(() ->
                files().export(List.of(new SnapshotFiles.StoredSnapshot("../../etc/passwd", "{}"))));
        assertThat(directory.resolve("../../etc/passwd.json").normalize()).doesNotExist();
    }

    private SnapshotFiles files() {
        return new SnapshotFiles(new SnapshotProperties(directory.toString()));
    }
}
