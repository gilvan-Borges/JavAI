package br.com.gilvanborges.javai.ai.indexer.sources;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Arrays;
import java.util.List;

/**
 * De onde a IA tira o que sabe, tudo por configuração: quem clonar o projeto troca estas propriedades pelas fontes
 * públicas dele. Nada aqui tem valor padrão para o dono atual. O indexador só LÊ o que for público e nunca reescreve o
 * texto: cada trecho é um pedaço literal da fonte, com o link de origem.
 *
 * @param siteDocuments  URLs https de texto, Markdown ou HTML do site (por exemplo {@code https://seu.site/llms-full.txt}).
 *                       Um site que só monta o conteúdo no navegador (SPA) precisa publicar um arquivo de texto assim,
 *                       porque o HTML dele quase não tem texto
 * @param documents      URLs https de documentos públicos, em PDF ou texto (o currículo, por exemplo)
 * @param githubUsers    usuários do GitHub: a IA lê o perfil, o README do perfil e a lista de repositórios públicos
 * @param githubRepos    repositórios extras ({@code dono/nome}, de usuário ou de organização) para ler o conteúdo
 * @param githubDiscover {@code true} lê também o conteúdo de TODOS os repositórios públicos (sem fork, sem arquivados) dos
 *                       usuários acima, até {@code githubMaxRepos}; {@code false} lê só os de {@code githubRepos} e os
 *                       ligados no snapshot do CRUD
 * @param githubExclude  repositórios a ignorar ({@code nome} ou {@code dono/nome}), na lista e na descoberta
 * @param githubMaxRepos teto de repositórios lidos por descoberta
 * @param linkedinUrl    perfil do LinkedIn. O LinkedIn não permite leitura automática: a URL entra só como link
 * @param siteUrl        o site principal, que também entra como link
 * @param checkInterval  de quanto em quanto tempo conferir se as fontes mudaram (o snapshot e os commits têm ciclo próprio)
 */
@ConfigurationProperties("indexer.sources")
public record SourcesProperties(
        @DefaultValue("") List<String> siteDocuments,
        @DefaultValue("") List<String> documents,
        @DefaultValue("") List<String> githubUsers,
        @DefaultValue("") List<String> githubRepos,
        @DefaultValue("false") boolean githubDiscover,
        @DefaultValue("") List<String> githubExclude,
        @DefaultValue("30") int githubMaxRepos,
        @DefaultValue("") String linkedinUrl,
        @DefaultValue("") String siteUrl,
        @DefaultValue("PT30M") java.time.Duration checkInterval) {

    public SourcesProperties {
        siteDocuments = split(siteDocuments);
        documents = split(documents);
        githubUsers = split(githubUsers);
        githubRepos = split(githubRepos);
        githubExclude = split(githubExclude);
        linkedinUrl = linkedinUrl == null ? "" : linkedinUrl.strip();
        siteUrl = siteUrl == null ? "" : siteUrl.strip();
    }

    /** Aceita lista ou "a,b,c" num valor só (variável de ambiente) e descarta vazios. */
    static List<String> split(List<String> values) {
        return values == null ? List.of() : values.stream().flatMap(v -> Arrays.stream(v.split(",")))
                .map(String::strip).filter(v -> !v.isEmpty()).toList();
    }

    /** Há alguma fonte pública configurada? */
    public boolean any() {
        return !siteDocuments.isEmpty() || !documents.isEmpty() || !githubUsers.isEmpty() || !githubRepos.isEmpty()
                || !linkedinUrl.isEmpty() || !siteUrl.isEmpty();
    }

    boolean excluded(String repoFullName) {
        String name = repoFullName.substring(repoFullName.indexOf('/') + 1);
        return githubExclude.stream().anyMatch(e -> e.equalsIgnoreCase(repoFullName) || e.equalsIgnoreCase(name));
    }
}
