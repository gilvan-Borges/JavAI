package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.UsageRecorder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** O cache de respostas: pergunta idêntica não paga o modelo de novo, e nada entra no cache sem passar pela guarda. */
class ChatCacheTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final Keys KEYS = new Keys("segredo-de-teste");

    private static final class CountingModel implements ChatModel {
        String text = "Ele usa RabbitMQ no apiSuporte [1].";
        int calls;

        @Override
        public Usage stream(PromptBuilder.Prompt prompt, List<String> models, Predicate<String> onDelta) throws IOException {
            calls++;
            boolean wanted = onDelta.test(text);
            return new Usage(models.get(0), 100, wanted ? 20 : 5, new BigDecimal("0.0002"));
        }
    }

    private record Event(String name, Object data) {
    }

    private static final class Sink implements EventSink {
        final List<Event> events = new ArrayList<>();

        @Override
        public boolean send(String event, Object data) {
            events.add(new Event(event, data));
            return true;
        }

        String text() {
            return events.stream().filter(e -> e.name().equals("delta"))
                    .map(e -> (String) ((Map<?, ?>) e.data()).get("text")).collect(Collectors.joining());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> named(String name) {
            return events.stream().filter(e -> e.name().equals(name)).map(e -> (Map<String, Object>) e.data()).findFirst().orElseThrow();
        }
    }

    private final CountingModel model = new CountingModel();
    private final ApiFixtures.MemoryStore store = new ApiFixtures.MemoryStore();
    private final HistorySigner signer = new HistorySigner(KEYS, CLOCK);
    private final ChatService service = build();

    private ChatService build() {
        var props = new ChatProperties(true, "k", "http://x", "http://x", List.of("rapido/a"), List.of("forte/a"),
                BigDecimal.ZERO, "", false, 300, "s", "http://x", List.of("gilvanborges.com.br"), "",
                new BigDecimal("1.00"), 100, 0, 12, 0.90, 3, 800, "https://gilvanborges.com.br");
        var aiProps = new AiApiProperties(List.of(), 60, Duration.ofSeconds(30), "/nao-existe", 1, "https://gilvanborges.com.br");
        var turnstile = new Turnstile(null, "http://x", "s", List.of()) {
            @Override
            public boolean verify(String token, String ip) {
                return true;
            }
        };
        return new ChatService(props, turnstile, (q, l) -> Optional.of(new Classification(Category.CODIGO, 0.9)), model,
                signer, KEYS, new UsageRecorder(store, CLOCK), ApiFixtures.holder(ApiFixtures.index()),
                new QueryEmbedder(aiProps), CLOCK);
    }

    private static ChatRequest question(String q) {
        return new ChatRequest(q, "pt", "token", List.of());
    }

    @Test
    void pergunta_identica_sai_do_cache_com_os_mesmos_eventos_texto_fontes_e_turno_assinado_e_custo_zero() {
        var first = new Sink();
        service.answer(question("Ele já usou RabbitMQ?"), first);
        assertThat(model.calls).isEqualTo(1);

        var second = new Sink();
        service.answer(question("  ele JÁ usou RabbitMQ?  "), second); // normalização: espaços e maiúsculas

        assertThat(model.calls).isEqualTo(1);
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.named("meta")).containsEntry("cached", true);
        var sources = (List<?>) second.events.stream().filter(e -> e.name().equals("sources")).findFirst().orElseThrow().data();
        assertThat(sources).isNotEmpty();
        var done = second.named("done");
        var turn = (ChatRequest.Turn) done.get("turn");
        assertThat(signer.verified(List.of(turn))).hasSize(1);
        assertThat(((Map<?, ?>) done.get("usage")).get("costUsd")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void pergunta_com_historico_nao_usa_o_cache_porque_o_prompt_muda() {
        var first = new Sink();
        service.answer(question("Ele já usou RabbitMQ?"), first);
        var turn = (ChatRequest.Turn) first.named("done").get("turn");

        service.answer(new ChatRequest("Ele já usou RabbitMQ?", "pt", "token", List.of(turn)), new Sink());

        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void resposta_retratada_pela_guarda_nao_entra_no_cache() {
        model.text = "Resposta com o canário " + KEYS.canary(PromptBuilder.VERSION) + " vazado [1].";
        var first = new Sink();
        service.answer(question("Ele já usou RabbitMQ?"), first);
        assertThat(first.events).extracting(Event::name).contains("retract");

        service.answer(question("Ele já usou RabbitMQ?"), new Sink());

        assertThat(model.calls).isEqualTo(2);
    }
}
