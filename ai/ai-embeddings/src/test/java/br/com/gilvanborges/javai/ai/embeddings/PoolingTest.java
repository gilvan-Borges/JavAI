package br.com.gilvanborges.javai.ai.embeddings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PoolingTest {

    @Test
    void media_so_dos_tokens_reais_e_normaliza() {
        // 3 tokens; o terceiro é preenchimento e não pode entrar na média.
        float[][] hidden = {{3f, 0f}, {1f, 0f}, {100f, 100f}};
        long[] mask = {1, 1, 0};

        float[] out = Pooling.meanPoolAndNormalize(hidden, mask);

        // média = (2, 0) -> normalizada = (1, 0)
        assertThat(out[0]).isCloseTo(1f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(out[1]).isCloseTo(0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void resultado_tem_norma_um() {
        float[] out = Pooling.meanPoolAndNormalize(new float[][] {{1f, 2f, 3f}, {4f, 5f, 6f}}, new long[] {1, 1});

        double norm = 0;
        for (float x : out) {
            norm += x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void sem_token_real_e_erro() {
        assertThatIllegalArgumentException().isThrownBy(() -> Pooling.meanPoolAndNormalize(new float[][] {{1f}}, new long[] {0}));
    }

    @Test
    void vetor_zerado_nao_vira_nan() {
        float[] out = Pooling.meanPoolAndNormalize(new float[][] {{0f, 0f}}, new long[] {1});

        assertThat(out).containsExactly(0f, 0f);
    }
}
