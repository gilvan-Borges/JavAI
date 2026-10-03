package br.com.gilvanborges.javai.ai.indexer;

import br.com.gilvanborges.javai.ai.indexer.sources.PublicSources;
import br.com.gilvanborges.javai.ai.indexer.store.IndexStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Porta interna do indexador, só para o painel /admin do ai-api (mesma rede do Docker, nunca publicada no Caddy).
 * Protegida por um token compartilhado: sem {@code indexer.trigger-token} configurado, tudo responde 404.
 * Não aceita parâmetro nenhum: não dá para mandar o indexador ler outra coisa, só reler o que já está configurado.
 */
@RestController
class InternalController {

    private final IndexerService service;
    private final IndexStore store;
    private final PublicSources sources;
    private final byte[] token;

    InternalController(IndexerService service, IndexStore store, PublicSources sources,
                       @Value("${indexer.trigger-token:}") String token) {
        this.service = service;
        this.store = store;
        this.sources = sources;
        this.token = token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/reindex")
    ResponseEntity<Map<String, Object>> reindex(@RequestHeader(value = "X-Indexer-Token", defaultValue = "") String given) {
        var denied = check(given);
        if (denied != null) {
            return denied;
        }
        if (service.running()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("estado", "ja-em-andamento"));
        }
        Thread.ofVirtual().name("reindex-manual").start(() -> service.run(IndexerService.Trigger.MANUAL));
        return ResponseEntity.accepted().body(Map.of("estado", "iniciada"));
    }

    @PostMapping("/internal/wipe")
    ResponseEntity<Map<String, Object>> wipe(@RequestHeader(value = "X-Indexer-Token", defaultValue = "") String given) {
        var denied = check(given);
        if (denied != null) {
            return denied;
        }
        var outcome = service.wipe();
        return outcome.status().equals("succeeded") ? ResponseEntity.ok(Map.of("estado", "apagada"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("estado", "nao-apagada"));
    }

    @GetMapping("/internal/status")
    ResponseEntity<Map<String, Object>> status(@RequestHeader(value = "X-Indexer-Token", defaultValue = "") String given) {
        var denied = check(given);
        if (denied != null) {
            return denied;
        }
        var s = sources.properties();
        var configured = new LinkedHashMap<String, Object>();
        configured.put("siteUrl", s.siteUrl());
        configured.put("siteDocuments", s.siteDocuments());
        configured.put("documents", s.documents());
        configured.put("githubUsers", s.githubUsers());
        configured.put("githubRepos", s.githubRepos());
        configured.put("githubDiscover", s.githubDiscover());
        configured.put("githubExclude", s.githubExclude());
        configured.put("githubMaxRepos", s.githubMaxRepos());
        configured.put("linkedinUrl", s.linkedinUrl());
        var body = new LinkedHashMap<String, Object>();
        body.put("rodando", service.running());
        body.put("fontes", configured);
        body.put("jobs", store.recentJobs(8));
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> check(String given) {
        if (token.length == 0) {
            return ResponseEntity.notFound().build();
        }
        if (!MessageDigest.isEqual(token, given.strip().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }
}
