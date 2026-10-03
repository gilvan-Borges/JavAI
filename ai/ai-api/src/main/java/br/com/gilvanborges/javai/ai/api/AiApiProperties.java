package br.com.gilvanborges.javai.ai.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Configuração da API da IA. Os valores padrão são os de produção.
 *
 * @param allowedOrigins     {@code Origin} aceitos no MCP. A ausência do cabeçalho é aceita (clientes MCP de
 *                           terminal e de desktop não o enviam); um {@code Origin} fora da lista leva 403.
 * @param rateLimitPerMinute chamadas por minuto por IP no MCP
 * @param reloadInterval     de quanto em quanto tempo conferir se há um índice ativo novo
 * @param modelDir           pasta do modelo de embeddings, que o ai-indexer baixa e verifica (aqui só é lida)
 * @param siteUrl            o portfólio, que aparece como link do perfil
 */
@ConfigurationProperties("aiapi")
public record AiApiProperties(
        @DefaultValue("https://gilvanborges.com.br") List<String> allowedOrigins,
        @DefaultValue("60") int rateLimitPerMinute,
        @DefaultValue("PT30S") Duration reloadInterval,
        @DefaultValue("/models") String modelDir,
        @DefaultValue("2") int embeddingThreads,
        @DefaultValue("https://gilvanborges.com.br") String siteUrl) {
}
