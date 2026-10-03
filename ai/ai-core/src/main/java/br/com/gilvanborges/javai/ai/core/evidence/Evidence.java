package br.com.gilvanborges.javai.ai.core.evidence;

/**
 * A prova de que um arquivo de um repositório público usa uma habilidade.
 *
 * @param skill    o nome da habilidade, igual ao cadastrado no CRUD
 * @param detector qual regra encontrou, para o relatório e para depurar
 * @param url      o link para a linha, fixado no commit lido (não no branch, que muda)
 */
public record Evidence(String skill, String repository, String path, int line, String detector, String url) {
}
