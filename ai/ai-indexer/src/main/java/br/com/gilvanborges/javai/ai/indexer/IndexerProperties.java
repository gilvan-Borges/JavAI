package br.com.gilvanborges.javai.ai.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuração do indexador. Os valores padrão são os de produção; os testes trocam as URLs para servidores locais.
 *
 * @param snapshotBaseUrl   onde o CRUD publica o snapshot (arquivo estático servido pelo Caddy)
 * @param pollInterval      de quanto em quanto tempo conferir se o snapshot mudou (GET condicional)
 * @param maxRepoSizeKb     acima disso o repositório não é clonado (o {@code size} do GitHub inclui o histórico)
 * @param maxChunksPerRepo  teto de trechos por repositório, para um repositório grande não dominar o índice
 * @param modelDir          pasta do modelo de embeddings (baixado e verificado por SHA-256 na primeira vez)
 * @param githubApiBase     base da API do GitHub, só para ler os metadados do repositório
 * @param githubCloneBase   base dos clones; o repositório é sempre {@code <base><dono>/<nome>.git}
 */
@ConfigurationProperties("indexer")
public record IndexerProperties(
        @DefaultValue("https://conteudo.gilvanborges.com.br") String snapshotBaseUrl,
        @DefaultValue("PT5M") Duration pollInterval,
        @DefaultValue("51200") int maxRepoSizeKb,
        @DefaultValue("300") int maxChunksPerRepo,
        @DefaultValue("/models") String modelDir,
        @DefaultValue("2") int embeddingThreads,
        @DefaultValue("https://api.github.com") String githubApiBase,
        @DefaultValue("https://github.com/") String githubCloneBase) {
}
