package br.com.gilvanborges.javai.ai.api.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guarda de saída (spec 01 e 04, LLM05/LLM07). O texto do modelo passa por aqui antes de chegar ao visitante.
 * Ela retém o fim do que foi gerado: um padrão proibido costuma chegar picado em vários pedaços ("ht", "tps://"), e
 * só se solta o que já não pode mais virar parte dele. Se algo proibido aparece, o resultado é {@code retract}:
 * o portfólio apaga o que mostrou e exibe uma mensagem fixa.
 *
 * <p>Proibido: URL (o modelo cita {@code [n]} e quem escreve links é o servidor), HTML, imagem ou link markdown,
 * o canário do prompt e uma citação {@code [n]} que não existe nas fontes.
 */
public final class OutputGuard {

    /** Quantos caracteres ficam retidos. Cobre o maior padrão partido ("javascript:", o canário, "[12]"). */
    static final int HOLDBACK = 40;

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(https?:|ftp:|www\\.|javascript:|data:text|<\\s*[a-z/!?]|!\\[|\\]\\(|&lt;|&#)");
    private static final Pattern BARE_HOST = Pattern.compile(
            "(?i)\\b[a-z0-9-]+\\.(com|org|io|dev|app|xyz|ru|cn|info|biz|ly)(\\.br)?(/|\\b)\\S*");
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private final String canary;
    private final int sources;
    private final StringBuilder held = new StringBuilder();
    private final StringBuilder all = new StringBuilder();
    private boolean retracted;

    public OutputGuard(String canary, int sources) {
        this.canary = canary;
        this.sources = sources;
    }

    /** O que pode ir ao visitante agora ({@code ""} se nada), ou {@code null} se a guarda disparou. */
    public String feed(String delta) {
        if (retracted) {
            return null;
        }
        held.append(delta);
        all.append(delta);
        if (violates(all)) {
            retracted = true;
            return null;
        }
        int releasable = held.length() - HOLDBACK;
        if (releasable <= 0) {
            return "";
        }
        // Não solta no meio de uma citação aberta: "[1" fica retido até o "]" chegar.
        int open = held.lastIndexOf("[", releasable - 1);
        if (open >= 0 && held.indexOf("]", open) < 0) {
            releasable = open;
        }
        if (releasable <= 0) {
            return "";
        }
        String out = held.substring(0, releasable);
        held.delete(0, releasable);
        return out;
    }

    /** Fim do texto: solta o que sobrou, ou {@code null} se a guarda dispara com o texto completo. */
    public String finish() {
        if (retracted || violates(all)) {
            retracted = true;
            return null;
        }
        String rest = held.toString();
        held.setLength(0);
        return rest;
    }

    public String fullText() {
        return all.toString();
    }

    private boolean violates(CharSequence text) {
        String s = text.toString();
        if (FORBIDDEN.matcher(s).find() || BARE_HOST.matcher(s).find()) {
            return true;
        }
        if (s.contains(canary) || (s.length() >= 5 && s.toUpperCase(java.util.Locale.ROOT).contains("CNRY-"))) {
            return true;
        }
        Matcher m = CITATION.matcher(s);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1).length() > 4 ? "9999" : m.group(1));
            if (n < 1 || n > sources) {
                return true;
            }
        }
        return false;
    }
}
