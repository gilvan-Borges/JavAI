package br.com.gilvanborges.javai.admin.repo;

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
 * A tela de repositórios do CRUD: um formulário que liga/desliga repositórios e publica, em vez de pedir o Swagger.
 * São dois arquivos estáticos do próprio jar, servidos sob a autenticação do Access como todo o resto: o HTML e um
 * script do mesmo domínio (o CSP do Caddy é {@code default-src 'self'}, então nada de script inline). O script só
 * chama a API JSON que já existe; a tela não tem lógica de servidor.
 */
@RestController
@Hidden
class RepositoryPage {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final MediaType JS = new MediaType("text", "javascript", StandardCharsets.UTF_8);

    private final byte[] html = read("admin-ui/repos.html");
    private final byte[] script = read("admin-ui/repos.js");

    @GetMapping("/admin/repos")
    ResponseEntity<byte[]> page() {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(html);
    }

    @GetMapping("/admin/repos.js")
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
