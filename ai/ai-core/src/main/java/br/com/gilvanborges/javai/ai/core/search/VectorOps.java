package br.com.gilvanborges.javai.ai.core.search;

/**
 * Produto escalar de dois vetores. Com vetores já normalizados ele é o cosseno. Há duas implementações, a escalar
 * (sempre disponível) e a SIMD pela Vector API, que ainda é módulo incubador e por isso fica isolada (spec 01).
 */
public interface VectorOps {

    float dot(float[] a, float[] b);

    String name();

    /** A SIMD quando o módulo incubador estiver carregado; a escalar caso contrário. */
    static VectorOps best() {
        try {
            return new SimdVectorOps();
        } catch (Throwable unavailable) {
            return new ScalarVectorOps();
        }
    }
}
