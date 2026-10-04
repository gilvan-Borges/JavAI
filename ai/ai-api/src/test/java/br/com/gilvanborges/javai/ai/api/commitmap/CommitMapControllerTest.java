package br.com.gilvanborges.javai.ai.api.commitmap;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommitMapControllerTest {

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

    private CommitMapController controller(boolean withIndex) {
        CommitMapStore store = perRepo -> List.of(
                new CommitMapStore.Commit("gilvan-Borges/controlei", "9d00098", Instant.parse("2026-10-03T14:21:00Z"), "fix(brain): um ponto"));
        var service = new CommitMapService(ApiFixtures.holder(withIndex ? ApiFixtures.index() : null), provider(store), CLOCK);
        return new CommitMapController(service);
    }

    @Test
    void o_mapa_sai_com_cache_publico_curto() {
        var response = controller(true).commitMap();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("public").contains("max-age=600");
        assertThat(response.getBody()).isInstanceOfSatisfying(Map.class,
                body -> assertThat(body.keySet()).containsExactly("updatedAt", "totals", "repos"));
    }

    @Test
    void sem_indice_o_mapa_responde_503_com_problem_details() {
        var response = controller(false).commitMap();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class,
                pd -> assertThat(pd.getStatus()).isEqualTo(503));
    }
}
