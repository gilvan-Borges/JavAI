package br.com.gilvanborges.javai.ai.api.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * O IP do visitante. Vem do {@code X-Real-IP}, que o Caddy escreve a partir do IP real da Cloudflare e que substitui
 * qualquer valor mandado pelo cliente; o contêiner só é alcançável pelo Caddy, então o cabeçalho não é forjável.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && real.length() <= 64) {
            return real.strip();
        }
        return request.getRemoteAddr();
    }
}
