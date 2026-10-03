package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.domain.publish.PortfolioNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Dispara o deploy hook do portfólio, que refaz o build lendo o arquivo público novo (spec 01). */
@Component
class HttpPortfolioNotifier implements PortfolioNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPortfolioNotifier.class);

    private final Optional<URI> hook;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    HttpPortfolioNotifier(PortfolioProperties properties) {
        this.hook = Optional.ofNullable(properties.deployHookUrl()).filter(url -> !url.isBlank()).map(URI::create);
    }

    @Override
    public boolean contentPublished(long version) {
        if (hook.isEmpty()) {
            LOG.info("Versão {} ativa; deploy hook do portfólio não configurado.", version);
            return false;
        }
        var request = HttpRequest.newBuilder(hook.get())
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 == 2) {
                return true;
            }
            LOG.warn("Deploy hook do portfólio respondeu {} para a versão {}.", status, version);
            return false;
        } catch (IOException e) {
            LOG.warn("Deploy hook do portfólio falhou para a versão {}: {}", version, e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
