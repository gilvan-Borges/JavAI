package br.com.gilvanborges.javai.ai.core.search;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Produto escalar com a Vector API: várias multiplicações por instrução, com FMA. Precisa de
 * {@code --add-modules jdk.incubator.vector} na compilação e na execução. Quem chama usa {@link VectorOps#best()},
 * que cai para a versão escalar quando o módulo não está carregado.
 */
public final class SimdVectorOps implements VectorOps {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public float dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Os vetores precisam ter o mesmo tamanho.");
        }
        int i = 0;
        int bound = SPECIES.loopBound(a.length);
        FloatVector acc = FloatVector.zero(SPECIES);
        for (; i < bound; i += SPECIES.length()) {
            acc = FloatVector.fromArray(SPECIES, a, i).fma(FloatVector.fromArray(SPECIES, b, i), acc);
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Override
    public String name() {
        return "SIMD (" + SPECIES.length() + " floats por instrução)";
    }
}
