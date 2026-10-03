package br.com.gilvanborges.javai.ai.core.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SearchMetricsTest {

    @Test
    void posicao_do_trecho_certo_e_zero_quando_nao_aparece() {
        assertThat(SearchMetrics.outcome("q", "b", List.of("a", "b", "c")).rank()).isEqualTo(2);
        assertThat(SearchMetrics.outcome("q", "z", List.of("a", "b", "c")).rank()).isZero();
        assertThat(SearchMetrics.outcome("q", "a", List.of()).rank()).isZero();
    }

    @Test
    void hit_at_k_e_mrr() {
        var outcomes = List.of(
                new SearchMetrics.Outcome("q1", 1),
                new SearchMetrics.Outcome("q2", 2),
                new SearchMetrics.Outcome("q3", 5),
                new SearchMetrics.Outcome("q4", 0));

        assertThat(SearchMetrics.hitAt(outcomes, 1)).isEqualTo(0.25);
        assertThat(SearchMetrics.hitAt(outcomes, 5)).isEqualTo(0.75);
        assertThat(SearchMetrics.mrr(outcomes)).isCloseTo((1.0 + 0.5 + 0.2 + 0) / 4, offset(1e-12));
    }

    @Test
    void lista_vazia_nao_divide_por_zero() {
        assertThat(SearchMetrics.hitAt(List.of(), 5)).isZero();
        assertThat(SearchMetrics.mrr(List.of())).isZero();
    }
}
