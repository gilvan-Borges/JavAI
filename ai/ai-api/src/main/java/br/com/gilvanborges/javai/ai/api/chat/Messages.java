package br.com.gilvanborges.javai.ai.api.chat;

/** Textos fixos do chat. São escritos pelo servidor, nunca pelo modelo. */
final class Messages {

    private Messages() {
    }

    static String refusal(String lang, String reason) {
        boolean pt = lang.equals("pt");
        if (reason.equals("attack")) {
            return pt ? "Não posso ajudar com isso. Posso responder perguntas sobre a carreira, os projetos e o código do Gilvan."
                    : "I can't help with that. I can answer questions about Gilvan's career, projects and code.";
        }
        return pt ? "Eu só respondo perguntas sobre o Gilvan: carreira, projetos, código e habilidades."
                : "I only answer questions about Gilvan: career, projects, code and skills.";
    }

    static String retract(String lang) {
        return lang.equals("pt") ? "Não consegui responder isso com segurança. Tente reformular a pergunta."
                : "I couldn't answer that safely. Try rephrasing the question.";
    }

    static String error(String lang) {
        return lang.equals("pt") ? "Algo deu errado. Tente de novo em instantes." : "Something went wrong. Please try again shortly.";
    }
}
