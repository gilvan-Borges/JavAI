package br.com.gilvanborges.javai.admin.content;

/** O que o admin envia para criar ou alterar um item. O status muda só pela rota de status. */
public interface ContentInput {

    Visibility visibility();
}
