package br.com.gilvanborges.javai.ai.api.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reconhece perguntas sobre atividade recente nos repositórios ("qual foi o último commit?", "o que ele fez esta semana?").
 *
 * <p>Esse tipo de pergunta quase nunca cita um repositório, então a busca por significado devolve resumos soltos de
 * alguns projetos e o modelo escolhe "o mais recente" entre os poucos que viu. Quando a pergunta é de atividade, o
 * documento que já compara todos os repositórios entra sempre entre as fontes (como o perfil entra nas perguntas de perfil).
 *
 * <p>É regra determinística e não uma categoria do Jev: não muda o que o classificador responde nem exige recalibrá-lo, e
 * tolera os erros de digitação comuns ("comit", "comite").
 */
public final class ActivityIntent {

    private static final List<Pattern> PATTERNS = List.of(
            // português: "último commit", "ultimo comit", "últimos pushes", "última vez que commitou"
            Pattern.compile("\\b(ultim\\w*|mais recente|recente\\w*)\\W+(\\w+\\W+){0,3}(com+it\\w*|push\\w*)"),
            Pattern.compile("\\b(com+it\\w*|push\\w*)\\W+(\\w+\\W+){0,3}(recente\\w*|ultim\\w*|hoje|ontem|agora|semana)"),
            Pattern.compile("\\bcom+it(ou|ando|ei|aram|ar)\\b"),
            Pattern.compile("\\batividade\\W+(\\w+\\W+){0,2}(recente\\w*|ultim\\w*)|\\bultima\\w*\\W+atividade"),
            Pattern.compile("\\b(fez|fazendo|faz|trabalhou|trabalhando|desenvolveu|desenvolvendo|codou|codando|mexeu|mexendo)\\W+"
                    + "(\\w+\\W+){0,3}(recentemente|ultimamente|hoje|ontem|agora|esta semana|essa semana|nessa semana|neste momento)"),
            Pattern.compile("\\b(projeto|repositorio|repo)\\W+(\\w+\\W+){0,2}mais recente|mais recente\\W+(\\w+\\W+){0,2}(projeto|repositorio|repo)"),
            // inglês
            Pattern.compile("\\b(latest|last|most recent|recent)\\W+(\\w+\\W+){0,3}(commit\\w*|push\\w*|activity|repo\\w*|project)"),
            Pattern.compile("\\bcommit(ted|ting)?\\W+(\\w+\\W+){0,3}(recently|lately|today|yesterday|this week|just now)"),
            Pattern.compile("\\bwhat\\W+(has|is|was)\\W+(he|gilvan)\\W+(been\\W+)?(working on|doing|building|coding)"),
            Pattern.compile("\\b(working on|doing|built|building)\\W+(\\w+\\W+){0,2}(lately|recently|now|today|this week)"));

    private ActivityIntent() {
    }

    public static boolean matches(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = normalize(question);
        for (Pattern p : PATTERNS) {
            if (p.matcher(q).find()) {
                return true;
            }
        }
        return false;
    }

    /** Minúsculas, sem acento: "Último COMMIT?" e "ultimo commit" ficam iguais. */
    static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return folded.toLowerCase(Locale.ROOT).strip();
    }
}
