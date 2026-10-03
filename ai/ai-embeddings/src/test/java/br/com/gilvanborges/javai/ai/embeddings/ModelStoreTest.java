package br.com.gilvanborges.javai.ai.embeddings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class ModelStoreTest {

    private static final byte[] CONTENT = "conteudo do modelo".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();
    private byte[] served = CONTENT;

    @TempDir
    Path directory;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            downloads.incrementAndGet();
            exchange.sendResponseHeaders(200, served.length);
            exchange.getResponseBody().write(served);
            exchange.close();
        });
        server.createContext("/ausente", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void baixa_confere_o_hash_e_guarda_o_arquivo() throws Exception {
        var file = file("/model", sha256(CONTENT));

        new ModelStore(directory).ensure(List.of(file));

        assertThat(Files.readAllBytes(directory.resolve("model.onnx"))).isEqualTo(CONTENT);
        assertThat(downloads).hasValue(1);
    }

    @Test
    void arquivo_ja_valido_no_disco_nao_e_baixado_de_novo() throws Exception {
        var file = file("/model", sha256(CONTENT));
        var store = new ModelStore(directory);

        store.ensure(List.of(file));
        store.ensure(List.of(file));

        assertThat(downloads).hasValue(1);
    }

    @Test
    void arquivo_adulterado_e_descartado_e_nao_fica_no_disco() {
        served = "conteudo ADULTERADO".getBytes(StandardCharsets.UTF_8);
        var file = file("/model", sha256(CONTENT));

        assertThatIOException().isThrownBy(() -> new ModelStore(directory).ensure(List.of(file)))
                .withMessageContaining("não confere");

        assertThat(directory.resolve("model.onnx")).doesNotExist();
        try (var listing = Files.list(directory)) {
            assertThat(listing.toList()).isEmpty(); // nem o temporário sobra
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void arquivo_corrompido_no_disco_e_baixado_de_novo() throws Exception {
        Files.writeString(directory.resolve("model.onnx"), "lixo");
        var file = file("/model", sha256(CONTENT));

        new ModelStore(directory).ensure(List.of(file));

        assertThat(Files.readAllBytes(directory.resolve("model.onnx"))).isEqualTo(CONTENT);
    }

    @Test
    void resposta_que_nao_e_200_e_erro() {
        var file = file("/ausente", sha256(CONTENT));

        assertThatIOException().isThrownBy(() -> new ModelStore(directory).ensure(List.of(file)))
                .withMessageContaining("HTTP 404");
    }

    @Test
    void o_modelo_oficial_esta_fixado_num_commit_e_com_hash() {
        assertThat(ModelStore.E5_SMALL_INT8).hasSize(2).allSatisfy(f -> {
            assertThat(f.url().toString()).startsWith("https://huggingface.co/").contains(ModelStore.E5_REVISION);
            assertThat(f.sha256()).hasSize(64).matches("[0-9a-f]+");
        });
    }

    private ModelStore.ModelFile file(String path, String sha) {
        return new ModelStore.ModelFile("model.onnx", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), sha);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
