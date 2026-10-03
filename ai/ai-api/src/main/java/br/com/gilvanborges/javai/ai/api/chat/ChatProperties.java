package br.com.gilvanborges.javai.ai.api.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;

/**
 * Configuração do chat (spec 01 e 04). Sem a chave do OpenRouter, a chave do Turnstile ou ao menos um modelo, o chat
 * responde 503 de manutenção e o resto do serviço (MCP) continua no ar. Nenhum valor secreto tem padrão.
 *
 * @param enabled             chave de emergência (JAVAI_CHAT_ENABLED): {@code false} desliga o chat sem derrubar o MCP
 * @param openrouterKey       chave do OpenRouter, usada no chat e no Jev (que também passa pelo OpenRouter)
 * @param modelsFast          modelos do nível {@code rapido}, o principal primeiro; o resto é fallback
 * @param modelsStrong        modelos do nível {@code forte}
 * @param reasoningEffort esforço de raciocínio enviado ao OpenRouter ("minimal", "low", "medium", "high"); vazio = não envia. Modelos de raciocínio gastam os tokens da resposta pensando.
 * @param zdr                 exige provedores com retenção zero de dados ("quando possível": pode excluir modelos)
 * @param maxPriceCompletion  teto de preço por milhão de tokens de saída, em dólares; 0 = sem teto do provedor
 * @param turnstileSecret     chave secreta do Turnstile
 * @param turnstileHostnames  hostnames aceitos no token (o do portfólio)
 * @param historySecret       segredo-mestre das assinaturas do histórico e das chaves do dia; vazio = aleatório a cada partida
 * @param dailyBudgetUsd      teto diário de gasto: o disjuntor abre aqui
 * @param perMinute           perguntas por minuto por IP
 * @param perDay              perguntas por dia por IP; 0 = sem limite diário (o teto de gasto do dia continua valendo)
 * @param banHours           por quantas horas bloquear quem tentou atacar o chat; 0 desliga o bloqueio
 * @param banConfidence      confiança mínima do Jev em "ataque" para bloquear na primeira tentativa
 * @param banStrikes         quantas recusas por ataque (de qualquer confiança acima do limiar do roteador) bloqueiam
 * @param jevTimeoutMs        passado disso, a pergunta segue com o nível {@code rapido}
 * @param corsOrigin          a origem do portfólio nas rotas /v1
 */
@ConfigurationProperties("aiapi.chat")
public record ChatProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String openrouterKey,
        @DefaultValue("https://openrouter.ai/api/v1") String openrouterBase,
        @DefaultValue("https://openrouter.ai/api/alpha/decisions") String jevUrl,
        @DefaultValue("") List<String> modelsFast,
        @DefaultValue("") List<String> modelsStrong,
        @DefaultValue("0") BigDecimal maxPriceCompletion,
        @DefaultValue("") String reasoningEffort,
        @DefaultValue("false") boolean zdr,
        @DefaultValue("300") int maxTokens,
        @DefaultValue("") String turnstileSecret,
        @DefaultValue("https://challenges.cloudflare.com/turnstile/v0/siteverify") String turnstileUrl,
        @DefaultValue("gilvanborges.com.br,www.gilvanborges.com.br") List<String> turnstileHostnames,
        @DefaultValue("") String historySecret,
        @DefaultValue("1.00") BigDecimal dailyBudgetUsd,
        @DefaultValue("3") int perMinute,
        @DefaultValue("0") int perDay,
        @DefaultValue("12") int banHours,
        @DefaultValue("0.90") double banConfidence,
        @DefaultValue("3") int banStrikes,
        @DefaultValue("800") int jevTimeoutMs,
        @DefaultValue("https://gilvanborges.com.br") String corsOrigin) {

    public ChatProperties {
        modelsFast = split(modelsFast);
        modelsStrong = split(modelsStrong);
        turnstileHostnames = split(turnstileHostnames);
    }

    /** Aceita tanto uma lista quanto "a,b" num valor só (o {@code @DefaultValue} e as variáveis de ambiente vêm assim) e descarta vazios. */
    static List<String> split(List<String> values) {
        return values == null ? List.of() : values.stream().flatMap(v -> java.util.Arrays.stream(v.split(",")))
                .map(String::strip).filter(v -> !v.isEmpty()).toList();
    }

    /** O chat só fala com o mundo se estiver ligado e com tudo que precisa. */
    public boolean ready() {
        return enabled && !openrouterKey.isBlank() && !turnstileSecret.isBlank() && !modelsFast.isEmpty()
                && !modelsStrong.isEmpty();
    }
}
