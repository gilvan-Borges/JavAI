package br.com.gilvanborges.javai.ai.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Entrada do {@code /mcp}: cabeçalhos de segurança em toda resposta e o rate limit por IP, antes de qualquer
 * trabalho de busca. O IP vem do {@code X-Real-IP}, que o Caddy escreve a partir do IP real da Cloudflare; o
 * contêiner só é alcançável pelo Caddy, então o cabeçalho não pode ser forjado de fora.
 */
public class McpGuardFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final Runnable onCall;

    /** @param onCall chamado a cada chamada aceita (conta as chamadas MCP do dia em {@code ops.usage_daily}) */
    public McpGuardFilter(RateLimiter limiter, Runnable onCall) {
        this.limiter = limiter;
        this.onCall = onCall;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        long retryAfter = limiter.tryAcquire(ClientIp.of(request));
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("application/problem+json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"/problems/rate-limited\",\"title\":\"Too many requests\","
                    + "\"status\":429,\"detail\":\"Limit of calls per minute reached. Try again shortly.\"}");
            return;
        }
        onCall.run();
        chain.doFilter(request, response);
    }
}
