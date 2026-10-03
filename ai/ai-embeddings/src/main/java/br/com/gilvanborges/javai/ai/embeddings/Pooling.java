package br.com.gilvanborges.javai.ai.embeddings;

/**
 * Pooling do e5: média dos vetores de cada token, só dos tokens reais (a máscara de atenção descarta o
 * preenchimento), seguida de normalização L2. É a mesma conta de {@code sentence-transformers}.
 */
final class Pooling {

    private Pooling() {
    }

    /**
     * @param hidden        saída do modelo: um vetor por token ({@code [tokens][dimensões]})
     * @param attentionMask 1 para token real, 0 para preenchimento
     */
    static float[] meanPoolAndNormalize(float[][] hidden, long[] attentionMask) {
        int dims = hidden[0].length;
        double[] sum = new double[dims];
        long count = 0;
        for (int t = 0; t < hidden.length; t++) {
            if (attentionMask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < dims; d++) {
                sum[d] += hidden[t][d];
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("Nenhum token real para fazer a média.");
        }
        double norm = 0;
        for (int d = 0; d < dims; d++) {
            sum[d] /= count;
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        float[] out = new float[dims];
        for (int d = 0; d < dims; d++) {
            out[d] = norm == 0 ? 0f : (float) (sum[d] / norm);
        }
        return out;
    }
}
