package br.com.gilvanborges.javai.ai.api.usage;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * Os contadores do "cérebro" (spec 08, {@code ops.knowledge_heat} e {@code ops.knowledge_pair}): quantas vezes a busca
 * escolheu cada trecho do índice e quais pares de trechos foram escolhidos na mesma resposta.
 *
 * <p>Só entram <b>origens de conhecimento</b> (conteúdo público do índice). A interface não tem como receber o texto de
 * uma pergunta, uma resposta, um IP ou um horário: a privacidade está no formato do dado, não numa promessa.
 */
public interface HeatStore {

    /** Um par de origens, sempre na ordem alfabética ({@code a < b}): a mesma combinação não existe de duas formas. */
    record Pair(String a, String b) {
        public Pair {
            if (a.compareTo(b) >= 0) {
                throw new IllegalArgumentException("a precisa vir antes de b.");
            }
        }

        public static Pair of(String x, String y) {
            return x.compareTo(y) < 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    /** O total de um intervalo de dias. */
    record Heat(Map<String, Integer> nodes, Map<Pair, Integer> pairs) {
        public static final Heat EMPTY = new Heat(Map.of(), Map.of());
    }

    /** Soma 1 em cada origem e em cada par distinto de origens de UMA resposta. Falhar aqui nunca pode afetar o chat. */
    void record(LocalDate day, Collection<String> sourceRefs);

    /** Os totais entre {@code from} e {@code to}, inclusive. */
    Heat between(LocalDate from, LocalDate to);

    /** Para quando não há banco (testes): não grava nada e devolve vazio. */
    HeatStore NOOP = new HeatStore() {
        @Override
        public void record(LocalDate day, Collection<String> sourceRefs) {
        }

        @Override
        public Heat between(LocalDate from, LocalDate to) {
            return Heat.EMPTY;
        }
    };
}
