package br.com.gilvanborges.javai.ai.core.source;

import java.util.regex.Pattern;

/**
 * Extrai o texto visível de um HTML de forma simples e segura: descarta script, estilo e comentários, troca as tags de
 * bloco por quebras de linha e decodifica as entidades comuns. Não executa nada e não segue link. Serve para páginas
 * que já vêm com o texto no HTML; um site que só monta o conteúdo no navegador (SPA) devolve quase nada aqui, e é
 * por isso que existe o arquivo de texto próprio (por exemplo {@code llms-full.txt}).
 */
public final class HtmlText {

    private static final Pattern NON_VISIBLE = Pattern.compile("(?is)<(script|style|noscript|template|svg|head)[^>]*>.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|section|article|header|footer|main|nav|li|ul|ol|br|tr|table|h[1-6]|pre|blockquote)\\b[^>]*>");
    private static final Pattern HEADING_OPEN = Pattern.compile("(?i)<h([1-6])\\b[^>]*>");
    private static final Pattern HEADING_CLOSE = Pattern.compile("(?i)</h[1-6]\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private HtmlText() {
    }

    /** {@code true} se o texto parece HTML (e não Markdown ou texto puro). */
    public static boolean looksLikeHtml(String text) {
        String head = text.length() > 2000 ? text.substring(0, 2000).toLowerCase(java.util.Locale.ROOT) : text.toLowerCase(java.util.Locale.ROOT);
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<body") || head.contains("<div") || head.contains("<p>");
    }

    /** Texto visível, com os títulos virando linhas de Markdown ({@code ## Título}) para o fatiador por seção funcionar. */
    public static String toText(String html) {
        String s = NON_VISIBLE.matcher(html).replaceAll(" ");
        s = HEADING_OPEN.matcher(s).replaceAll(m -> "\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " ");
        s = HEADING_CLOSE.matcher(s).replaceAll("\n\n");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decode(s);
        // Colapsa espaços sem apagar as quebras de linha que separam os blocos.
        s = s.replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    private static String decode(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&middot;", "·")
                .replace("&ndash;", "–").replace("&mdash;", "—").replace("&hellip;", "…");
    }
}
