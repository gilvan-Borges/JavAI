package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.domain.content.Lang;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Exporta o snapshot ativo como arquivos estáticos ({@code pt.json} e {@code en.json}). É assim que o conteúdo
 * sai do CRUD: o Caddy serve estes arquivos, e o CRUD fica sem nenhuma rota pública.
 *
 * <p>Todos os idiomas são gravados primeiro em temporários e só depois trocados com {@code ATOMIC_MOVE}, então
 * quem lê nunca encontra um arquivo pela metade.
 */
@Component
class SnapshotFiles {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path directory;

    SnapshotFiles(SnapshotProperties properties) {
        this.directory = Path.of(properties.directory());
    }

    void export(List<StoredSnapshot> snapshots) {
        try {
            Files.createDirectories(directory);
            var staged = new ArrayList<Path[]>();
            for (var snapshot : snapshots) {
                // O nome do arquivo vem só do enum: nada vindo de fora vira caminho.
                Lang lang = Lang.fromCode(snapshot.lang())
                        .orElseThrow(() -> new IllegalStateException("Idioma inválido no snapshot."));
                Path temp = Files.createTempFile(directory, "." + lang.code() + "-", ".tmp");
                Files.writeString(temp, snapshot.body(), StandardCharsets.UTF_8);
                if (POSIX) {
                    // O Caddy roda com outro usuário e sem CAP_DAC_OVERRIDE: precisa de leitura para todos.
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
                }
                staged.add(new Path[] {temp, directory.resolve(lang.code() + ".json")});
            }
            for (Path[] move : staged) {
                Files.move(move[0], move[1], StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao exportar o snapshot público.", e);
        }
    }

    record StoredSnapshot(String lang, String body) {
    }
}
