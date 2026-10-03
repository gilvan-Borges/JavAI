package br.com.gilvanborges.javai.ai.api.admin;

/** Escape de HTML. Tudo que vem de dado (título de trecho, nome de repositório, resposta de busca) passa por aqui. */
final class Html {

    private Html() {
    }

    static String esc(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        var sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
