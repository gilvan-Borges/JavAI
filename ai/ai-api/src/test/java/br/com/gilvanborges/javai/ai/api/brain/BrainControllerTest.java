package br.com.gilvanborges.javai.ai.api.brain;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.chat.Keys;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.HeatStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrainControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private BrainController controller(boolean withIndex, int probePerMinute) {
        var aiProps = new AiApiProperties(List.of(), 60, Duration.ofSeconds(30), "/nao-existe", 1, "https://gilvanborges.com.br");
        var props = new BrainProperties(3, 30, 1, 60, probePerMinute);
        var service = new BrainService(ApiFixtures.holder(withIndex ? ApiFixtures.index() : null), provider(HeatStore.NOOP),
                provider(new ApiFixtures.MemoryStore()), new QueryEmbedder(aiProps), CLOCK, props);
        return new BrainController(service, new Keys("segredo"), CLOCK);
    }

    private static MockHttpServletRequest from(String ip) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", ip);
        return request;
    }

    @Test
    void o_mapa_sai_com_cache_publico_curto() {
        var response = controller(true, 10).brain();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("public").contains("max-age=600");
    }

    @Test
    void sem_indice_o_mapa_responde_503_com_problem_details() {
        var response = controller(false, 10).brain();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void a_sonda_valida_o_tamanho_da_pergunta() {
        var c = controller(true, 10);

        assertThat(c.probe(new BrainController.ProbeRequest("ab", "pt"), from("1.1.1.1")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.probe(new BrainController.ProbeRequest("x".repeat(201), "pt"), from("1.1.1.1")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.probe(new BrainController.ProbeRequest(null, "pt"), from("1.1.1.1")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.probe(new BrainController.ProbeRequest("Ele usa RabbitMQ?", "pt"), from("1.1.1.1")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void a_sonda_nunca_vai_para_o_cache() {
        var response = controller(true, 10).probe(new BrainController.ProbeRequest("Ele usa RabbitMQ?", null), from("1.1.1.1"));

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void a_sonda_tem_limite_por_visitante_e_um_visitante_nao_bloqueia_o_outro() {
        var c = controller(true, 2);
        var q = new BrainController.ProbeRequest("Ele usa RabbitMQ?", "pt");

        assertThat(c.probe(q, from("2.2.2.2")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(c.probe(q, from("2.2.2.2")).getStatusCode()).isEqualTo(HttpStatus.OK);
        var limited = c.probe(q, from("2.2.2.2"));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(c.probe(q, from("3.3.3.3")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
