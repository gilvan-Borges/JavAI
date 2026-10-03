package br.com.gilvanborges.javai.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Proteção contra CSRF (spec 03 e 04).
 *
 * <p>O Cloudflare Access injeta o JWT a partir de um cookie, então o navegador manda a credencial sozinho e
 * CSRF é um risco real. Duas travas, para todo método que altera dados:
 * <ul>
 *   <li>Fetch Metadata: se o navegador diz que a requisição veio de outro site (ou de outro subdomínio, como
 *       o portfólio), ela é recusada.</li>
 *   <li>Só JSON: um formulário de outro site não consegue mandar {@code application/json} sem preflight, e o
 *       CORS do admin é fechado.</li>
 * </ul>
 */
public class CrossSiteRequestFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (UNSAFE_METHODS.contains(request.getMethod())) {
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (fetchSite != null && !ALLOWED_FETCH_SITES.contains(fetchSite)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "cross-site",
                        "Requisições de outros sites não são aceitas.");
                return;
            }
            if (hasBody(request) && !isJson(request.getContentType())) {
                reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                        "O admin só aceita application/json.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, int status, String type, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Texto fixo, sem nada vindo da requisição.
        response.getWriter().write(
                "{\"type\":\"/problems/" + type + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
