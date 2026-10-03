package br.com.gilvanborges.javai.admin.publish;

public class PublishInProgressException extends RuntimeException {

    public PublishInProgressException() {
        super("Já existe uma publicação em andamento.");
    }
}
