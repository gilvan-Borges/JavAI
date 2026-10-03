package br.com.gilvanborges.javai.ai.embeddings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Garante que os arquivos do modelo estão no disco e íntegros. Cada arquivo tem um SHA-256 fixado no código: se o
 * que chegou da internet (ou o que já estava no disco) não bater, o app não sobe. Isso fecha a porta da cadeia de
 * suprimentos para o modelo (spec 04, LLM03). O download vai para um arquivo temporário e só é movido para o
 * lugar definitivo depois da verificação.
 */
public final class ModelStore {

    /** Um arquivo do modelo: de onde baixar e o hash que ele precisa ter. */
    public record ModelFile(String name, URI url, String sha256) {
    }

    /** O multilingual-e5-small quantizado em int8, fixado num commit do repositório (imutável). */
    public static final String E5_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3";

    private static final String E5_BASE = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/" + E5_REVISION + "/";

    public static final List<ModelFile> E5_SMALL_INT8 = List.of(
            new ModelFile("model.onnx", URI.create(E5_BASE + "onnx/model_qint8_avx512_vnni.onnx"),
                    "dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88"),
            new ModelFile("tokenizer.json", URI.create(E5_BASE + "tokenizer.json"),
                    "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"));

    private final Path directory;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ModelStore(Path directory) {
        this.directory = directory;
    }

    /** Baixa o que faltar, confere o hash de tudo e devolve a pasta. Lança exceção se algum hash não bater. */
    public Path ensure(List<ModelFile> files) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        for (ModelFile file : files) {
            Path target = directory.resolve(file.name());
            if (Files.exists(target) && sha256(target).equals(file.sha256())) {
                continue;
            }
            download(file, target);
        }
        return directory;
    }

    private void download(ModelFile file, Path target) throws IOException, InterruptedException {
        Path temp = Files.createTempFile(directory, file.name() + ".", ".part");
        try {
            var request = HttpRequest.newBuilder(file.url()).timeout(Duration.ofMinutes(15)).GET().build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                throw new IOException("Download de " + file.name() + " falhou: HTTP " + response.statusCode());
            }
            String actual = sha256(temp);
            if (!actual.equals(file.sha256())) {
                throw new IOException("SHA-256 de " + file.name() + " não confere: esperado " + file.sha256()
                        + ", recebido " + actual + ". O arquivo foi descartado.");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(in, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre existe no JDK.", e);
        }
    }
}
