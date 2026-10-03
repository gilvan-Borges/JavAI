package br.com.gilvanborges.javai.ai.embeddings;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roda de verdade o modelo, então só entra quando JAVAI_MODEL_DIR aponta para a pasta com model.onnx e
 * tokenizer.json (no CI o modelo é baixado e verificado pelo ModelStore antes).
 */
@EnabledIfEnvironmentVariable(named = "JAVAI_MODEL_DIR", matches = ".+")
class OnnxEmbedderTest {

    private static OnnxEmbedder embedder;

    @BeforeAll
    static void load() {
        embedder = new OnnxEmbedder(Path.of(System.getenv("JAVAI_MODEL_DIR")), 2);
    }

    @AfterAll
    static void close() {
        embedder.close();
    }

    @Test
    void vetor_tem_384_dimensoes_e_norma_um() {
        float[] v = embedder.embedQuery("Onde o Gilvan trabalha?");

        assertThat(v).hasSize(OnnxEmbedder.DIMENSIONS);
        assertThat(norm(v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void o_mesmo_texto_da_sempre_o_mesmo_vetor() {
        assertThat(embedder.embedQuery("Spring Boot e RabbitMQ")).containsExactly(embedder.embedQuery("Spring Boot e RabbitMQ"));
    }

    @Test
    void pergunta_fica_mais_perto_do_trecho_certo_do_que_de_um_trecho_qualquer() {
        float[] query = embedder.embedQuery("Onde o Gilvan trabalha hoje?");
        float[] certo = embedder.embedPassage("Experiência de Gilvan Borges na Vibetex. Hoje sou engenheiro de software na Vibetex.");
        float[] errado = embedder.embedPassage("Receita de bolo de chocolate com cobertura de brigadeiro.");

        assertThat(cos(query, certo)).isGreaterThan(cos(query, errado) + 0.05);
    }

    @Test
    void funciona_entre_idiomas() {
        float[] queryPt = embedder.embedQuery("Que tecnologias de mensageria ele usa?");
        float[] certoEn = embedder.embedPassage("He uses RabbitMQ with dead letter queues and retries for asynchronous messaging.");
        float[] erradoEn = embedder.embedPassage("The weather in Rio de Janeiro is hot and humid in summer.");

        assertThat(cos(queryPt, certoEn)).isGreaterThan(cos(queryPt, erradoEn) + 0.03);
    }

    @Test
    void texto_longo_e_truncado_sem_falhar_e_texto_vazio_funciona() {
        assertThat(embedder.embedPassage("palavra ".repeat(3000))).hasSize(OnnxEmbedder.DIMENSIONS);
        assertThat(embedder.embedPassage("")).hasSize(OnnxEmbedder.DIMENSIONS);
    }

    @Test
    void chamadas_em_paralelo_dao_o_mesmo_resultado_que_em_sequencia() throws Exception {
        List<String> texts = List.of("Java 21", "Spring Boot", "RabbitMQ", "PostgreSQL com RLS", "Angular e Flutter", "Testcontainers");
        var expected = texts.stream().map(embedder::embedPassage).toList();

        var actual = texts.parallelStream().map(embedder::embedPassage).toList();

        for (int i = 0; i < texts.size(); i++) {
            assertThat(actual.get(i)).containsExactly(expected.get(i));
        }
    }

    private static double cos(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    private static double norm(float[] v) {
        return Math.sqrt(cos(v, v));
    }
}
