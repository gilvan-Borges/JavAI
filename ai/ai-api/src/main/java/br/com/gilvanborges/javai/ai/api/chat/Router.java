package br.com.gilvanborges.javai.ai.api.chat;

import java.util.Optional;

/**
 * Decide o que fazer com a pergunta (spec 01, "Roteador"). Os limiares são o ponto de partida e a bateria de
 * avaliação os calibra, com a regra de que nenhuma pergunta legítima pode ser barrada.
 */
public final class Router {

    /**
     * Calibrado com a bateria real (evals/relatorios): perguntas legítimas que o Jev confundiu com "fora do escopo"
     * tiveram confiança de 0,57 a 0,79; as realmente fora do escopo, de 0,81 a 0,99. Abaixo disso a pergunta segue
     * e o prompt do modelo (regra 7) ainda a mantém no tema.
     */
    static final double OFF_TOPIC_THRESHOLD = 0.80;
    static final double ATTACK_THRESHOLD = 0.5;

    public static double offTopicThreshold() {
        return OFF_TOPIC_THRESHOLD;
    }

    public static double attackThreshold() {
        return ATTACK_THRESHOLD;
    }

    public enum Action { ANSWER, REFUSE }

    public enum Tier { RAPIDO, FORTE }

    /** @param category a categoria usada ({@code null} se o Jev não respondeu) */
    public record Decision(Action action, Tier tier, Category category, double confidence, String refusalReason) {
    }

    private Router() {
    }

    public static Decision decide(Optional<Classification> classification) {
        // Sem o Jev (erro, cota ou demora), a pergunta segue no nível rápido e as outras camadas continuam valendo.
        if (classification.isEmpty()) {
            return new Decision(Action.ANSWER, Tier.RAPIDO, null, 0, null);
        }
        Classification c = classification.get();
        if (c.category() == Category.ATAQUE && c.confidence() >= ATTACK_THRESHOLD) {
            return new Decision(Action.REFUSE, null, c.category(), c.confidence(), "attack");
        }
        if (c.category() == Category.FORA_DO_ESCOPO && c.confidence() >= OFF_TOPIC_THRESHOLD) {
            return new Decision(Action.REFUSE, null, c.category(), c.confidence(), "off_topic");
        }
        Tier tier = c.category() == Category.CODIGO || c.category() == Category.VAGA ? Tier.FORTE : Tier.RAPIDO;
        return new Decision(Action.ANSWER, tier, c.category(), c.confidence(), null);
    }
}
