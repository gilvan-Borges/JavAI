package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import br.com.gilvanborges.javai.ai.api.usage.UsageRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Liga as peças do chat. As rotas {@code /v1/*} têm CORS só para o portfólio, com {@code GET}, {@code POST} e
 * {@code OPTIONS}, só o cabeçalho {@code Content-Type}, sem credenciais e {@code Max-Age} de 600. O CORS não protege a
 * API (um curl o ignora): quem protege é o Turnstile, o rate limit e o teto de gasto. O MCP não tem CORS.
 */
@Configuration
public class ChatConfig implements WebMvcConfigurer {

    private final ChatProperties props;

    public ChatConfig(ChatProperties props) {
        this.props = props;
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/Sao_Paulo"));
    }

    @Bean
    Keys keys() {
        return new Keys(props.historySecret());
    }

    @Bean
    HistorySigner historySigner(Keys keys, Clock clock) {
        return new HistorySigner(keys, clock);
    }

    @Bean
    ChatService chatService(Keys keys, HistorySigner signer, UsageRecorder usage, IndexHolder index, QueryEmbedder embedder,
                            Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new ChatService(props,
                new Turnstile(http, props.turnstileUrl(), props.turnstileSecret(), props.turnstileHostnames()),
                new JevClassifier(http, props.jevUrl(), props.openrouterKey(), Duration.ofMillis(props.jevTimeoutMs() + 700L)),
                new OpenRouterClient(http, props.openrouterBase(), props.openrouterKey(), props.maxTokens(),
                        props.maxPriceCompletion(), props.zdr(), props.reasoningEffort()),
                signer, keys, usage, index, embedder, clock);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOrigins(props.corsOrigin())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(600);
    }
}
