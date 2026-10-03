package br.com.gilvanborges.javai.admin.domain.publish;

/** Porta: avisa o portfólio que há conteúdo novo, para ele refazer o build. */
public interface PortfolioNotifier {

    /** Nunca lança exceção: devolve {@code false} se o aviso não chegou ou não está configurado. */
    boolean contentPublished(long version);
}
