package br.com.gilvanborges.javai.ai.api.chat;

import java.util.Optional;

/** Classifica a pergunta. Vazio quando o classificador falha ou demora: o roteador então segue sem ele. */
public interface QuestionClassifier {

    Optional<Classification> classify(String question, String lang);
}
