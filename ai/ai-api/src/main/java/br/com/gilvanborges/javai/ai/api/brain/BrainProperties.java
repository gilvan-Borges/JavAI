package br.com.gilvanborges.javai.ai.api.brain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * O "cérebro" (spec 08).
 *
 * @param minHits        quantas vezes um trecho (ou um par) precisa ter sido escolhido, no período, para aparecer com
 *                       número. Abaixo disso o ponto fica apagado: com pouco tráfego, um número pequeno pode ser UMA visita
 * @param windowDays     quantos dias o mapa soma
 * @param delayDays      quantos dias recentes ficam de fora. Com 1, hoje nunca aparece: não dá para comparar dois retratos
 *                       e descobrir o que UMA pessoa acabou de perguntar
 * @param cacheMinutes   por quanto tempo o mapa montado é reaproveitado
 * @param probePerMinute quantas sondas ("onde minha pergunta cai?") por minuto por visitante
 */
@ConfigurationProperties("aiapi.brain")
public record BrainProperties(
        @DefaultValue("3") int minHits,
        @DefaultValue("30") int windowDays,
        @DefaultValue("1") int delayDays,
        @DefaultValue("60") int cacheMinutes,
        @DefaultValue("10") int probePerMinute) {
}
