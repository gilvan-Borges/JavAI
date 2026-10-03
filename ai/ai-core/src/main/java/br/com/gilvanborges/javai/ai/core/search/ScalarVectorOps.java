package br.com.gilvanborges.javai.ai.core.search;

/** Versão de referência: um laço comum. Serve de plano B e de base para medir o ganho da versão SIMD. */
public final class ScalarVectorOps implements VectorOps {

    @Override
    public float dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Os vetores precisam ter o mesmo tamanho.");
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Override
    public String name() {
        return "escalar";
    }
}
