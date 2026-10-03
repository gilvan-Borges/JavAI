package br.com.gilvanborges.javai.ai.api.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Porteiro do {@code /admin}. Sem credenciais configuradas a rota nem existe (404). Com elas, tudo exige sessão válida,
 * menos a tela de login. Todas as respostas saem com cabeçalhos que proíbem cache, iframe, script e recurso externo.
 */
public class AdminFilter extends OncePerRequestFilter {

    private final AdminProperties props;
    private final AdminSession session;

    public AdminFilter(AdminProperties props, AdminSession session) {
        this.props = props;
        this.session = session;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        // Sem script e sem nada de fora: só o próprio HTML e o CSS embutido.
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");

        String path = request.getRequestURI();
        if (path.equals("/admin/login") || session.valid(sessionCookie(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(303);
        response.setHeader("Location", "/admin/login");
    }

    private static String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AdminSession.COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
