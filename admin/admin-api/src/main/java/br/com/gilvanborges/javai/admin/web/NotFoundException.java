package br.com.gilvanborges.javai.admin.web;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity) {
        super("Registro não encontrado: " + entity + ".");
    }
}
