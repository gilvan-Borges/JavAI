package br.com.gilvanborges.javai.admin.domain.publish;

/** A publicação foi recusada por uma regra de negócio. A mensagem pode ser mostrada ao admin. */
public class PublishRejectedException extends RuntimeException {

    public PublishRejectedException(String message) {
        super(message);
    }
}
