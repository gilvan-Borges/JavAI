package br.com.gilvanborges.javai.ai.embeddings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mede o que a quantização em int8 custa em qualidade (spec 01: "a troca só acontece se a avaliação não mostrar
 * perda"). Compara o modelo int8 com o fp32 nos mesmos textos. Só roda com as duas pastas de modelo:
 * JAVAI_MODEL_DIR (int8) e JAVAI_MODEL_FP32_DIR (fp32, 470 MB, baixado à mão).
 */
@EnabledIfEnvironmentVariable(named = "JAVAI_MODEL_FP32_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JAVAI_MODEL_DIR", matches = ".+")
class QuantizationQualityTest {

    private static final List<String> PASSAGES = List.of(
            "Experiência de Gilvan Borges na Vibetex. Engenheiro de software com foco em arquitetura de sistemas Java.",
            "Gilvan Borges' experience at Vibetex. Software engineer focused on Java systems architecture.",
            "Projeto apiSuporte: microsserviço de tickets e SLA que publica eventos no RabbitMQ.",
            "Project apiSuporte: ticketing and SLA microservice that publishes events to RabbitMQ.",
            "Habilidade de Gilvan Borges: Transactional Outbox. Evento gravado na mesma transação do dado.",
            "Skill: Row-Level Security. Per-firm isolation enforced by the database from the token.",
            "Formação Java Web Developer na COTI Informática em 2024.",
            "Aplicativo Flutter offline-first com fila local cifrada e sincronização resiliente.",
            "FightConnect: SaaS para academias de artes marciais com 6 serviços Java, front Angular e app.",
            "Cedae: operação de campo offline com painel Angular, duas APIs .NET e BI com Metabase.",
            "Receita de bolo de chocolate com cobertura de brigadeiro.",
            "O clima no Rio de Janeiro é quente e úmido no verão.",
            "Tabela de preços do mercado de ações em 2025.",
            "Como treinar um cachorro para sentar e ficar.");

    private static final List<String> QUERIES = List.of(
            "Onde o Gilvan trabalha hoje?",
            "Where does he work?",
            "Ele usa RabbitMQ?",
            "Does he know Row-Level Security?",
            "Qual a formação dele?",
            "Ele tem experiência com mobile offline?",
            "Qual projeto mostra o trabalho dele?",
            "Quais tecnologias ele usou no Cedae?");

    @Test
    void int8_perde_pouco_em_vetor_e_quase_nada_em_ranking() {
        try (var int8 = new OnnxEmbedder(Path.of(System.getenv("JAVAI_MODEL_DIR")), 2);
             var fp32 = new OnnxEmbedder(Path.of(System.getenv("JAVAI_MODEL_FP32_DIR")), 2)) {

            double sum = 0, min = 1;
            int n = 0;
            for (String text : PASSAGES) {
                double c = dot(int8.embedPassage(text), fp32.embedPassage(text));
                sum += c;
                min = Math.min(min, c);
                n++;
            }
            for (String text : QUERIES) {
                double c = dot(int8.embedQuery(text), fp32.embedQuery(text));
                sum += c;
                min = Math.min(min, c);
                n++;
            }
            double mean = sum / n;

            int agree = 0;
            for (String query : QUERIES) {
                if (top1(int8, query) == top1(fp32, query)) {
                    agree++;
                }
            }
            double agreement = (double) agree / QUERIES.size();
            System.out.printf("QUANTIZAÇÃO int8 vs fp32: cosseno médio %.4f, mínimo %.4f | mesmo 1º lugar em %d de %d perguntas (%.0f%%)%n",
                    mean, min, agree, QUERIES.size(), agreement * 100);

            assertThat(mean).isGreaterThan(0.97);
            assertThat(min).isGreaterThan(0.93);
            assertThat(agreement).isGreaterThanOrEqualTo(0.85);
        }
    }

    private static int top1(OnnxEmbedder model, String query) {
        float[] q = model.embedQuery(query);
        int best = -1;
        double bestScore = -2;
        for (int i = 0; i < PASSAGES.size(); i++) {
            double s = dot(q, model.embedPassage(PASSAGES.get(i)));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
