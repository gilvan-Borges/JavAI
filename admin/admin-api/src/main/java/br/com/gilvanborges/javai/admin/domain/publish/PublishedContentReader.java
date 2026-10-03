package br.com.gilvanborges.javai.admin.domain.publish;

import br.com.gilvanborges.javai.admin.domain.content.PublishedContent;

/** Porta: lê só o conteúdo publicado e público, com os campos da lista permitida. */
public interface PublishedContentReader {

    /** O perfil vem nulo quando não está publicado ou não é público. */
    PublishedContent read();
}
