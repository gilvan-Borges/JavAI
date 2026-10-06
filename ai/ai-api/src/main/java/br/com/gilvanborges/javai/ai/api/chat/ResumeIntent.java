package br.com.gilvanborges.javai.ai.api.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Reconhece perguntas sobre o currículo ("me faz um resumo do currículo dele", "posso ver o CV?", "summarize his resume").
 *
 * <p>O currículo em PDF vira vários trechos, e a busca devolve só alguns: um pedido de resumo receberia metade do
 * documento. Quando a pergunta é sobre o currículo, todos os trechos dele entram entre as fontes (como o documento de
 * atividade entra nas perguntas de commit, ver {@link ActivityIntent}).
 *
 * <p>"Resume" sozinho não basta: em português é o verbo ("me resume os projetos"). Em inglês, só com artigo ou possessivo.
 */
public final class ResumeIntent {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\bcurr?icul\\w*"),
            Pattern.compile("\\bcvs?\\b"),
            Pattern.compile("\\b(his|the|your|gilvan'?s|a)\\W+(resume|cv)\\b"),
            Pattern.compile("\\bresume\\W+(summary|overview|pdf|download)"));

    private ResumeIntent() {
    }

    public static boolean matches(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = ActivityIntent.normalize(question);
        for (Pattern p : PATTERNS) {
            if (p.matcher(q).find()) {
                return true;
            }
        }
        return false;
    }
}
