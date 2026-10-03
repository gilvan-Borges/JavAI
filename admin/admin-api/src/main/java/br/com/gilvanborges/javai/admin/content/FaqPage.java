package br.com.gilvanborges.javai.admin.content;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A tela de FAQ do CRUD: é por ela que o dono corrige o que a IA responde errado (escreve a resposta certa, publica,
 * e a IA passa a citá-la). Mesmo desenho da tela de repositórios: HTML e script do mesmo domínio (CSP
 * {@code default-src 'self'}), em cima da API JSON de {@code /admin/v1/faqs}, sob o JWT do Access.
 */
@RestController
@Hidden
class FaqPage {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final MediaType JS = new MediaType("text", "javascript", StandardCharsets.UTF_8);

    private final byte[] html = read("admin-ui/faqs.html");
    private final byte[] script = read("admin-ui/faqs.js");

    @GetMapping("/admin/faqs")
    ResponseEntity<byte[]> page() {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(html);
    }

    @GetMapping("/admin/faqs.js")
    ResponseEntity<byte[]> script() {
        return ResponseEntity.ok().contentType(JS).cacheControl(CacheControl.noStore()).body(script);
    }

    private static byte[] read(String path) {
        try {
            return new ClassPathResource(path).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Faltou o recurso " + path + " no jar.", e);
        }
    }
}
