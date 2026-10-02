package br.com.gilvanborges.javai.core.publish;

import java.util.Map;

/** O resultado de uma publicação: a versão ativada, quanto entrou e se o portfólio foi avisado. */
public record PublishOutcome(long indexVersion, Map<String, Integer> counts, boolean portfolioNotified) {

    public PublishOutcome {
        counts = Map.copyOf(counts);
    }
}
