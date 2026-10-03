package br.com.gilvanborges.javai.ai.api.mcp;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.web.McpGuardFilter;
import br.com.gilvanborges.javai.ai.api.web.RateLimiter;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Monta o servidor MCP (spec 03): transporte Streamable HTTP sem estado, só {@code POST /mcp}. Sem sessão, não há
 * nada para sequestrar nem para vazar entre clientes; cada chamada é independente. O {@code GET} responde 405 pelo
 * próprio transporte, e um {@code Origin} fora da lista leva 403.
 */
@Configuration
public class McpServerConfig {

    /** O corpo mais longo é uma descrição de vaga de 8.000 caracteres; 64 KB sobra e barra o abuso. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    @Bean
    HttpServletStatelessServerTransport mcpTransport(AiApiProperties properties) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .messageEndpoint("/mcp")
                .maxRequestSize(MAX_BODY_BYTES)
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(properties.allowedOrigins())
                        .build())
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, McpTools tools, McpPrompts prompts) {
        return McpServer.sync(transport)
                .serverInfo("javai", "0.1.0")
                .instructions("""
                        Read-only access to the public professional profile of Gilvan Borges (Java/Spring Boot developer) \
                        and to evidence found in his public GitHub repositories. Every tool accepts lang=en (default) or pt. \
                        Answer only with what the tools return; when a technology has no evidence, say so.""")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .tools(tools.specifications())
                .prompts(prompts.specifications())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport,
                                                                           McpStatelessSyncServer server) {
        // O servidor precisa existir antes de o servlet receber chamadas (ele registra o handler no transporte).
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("mcp");
        registration.setAsyncSupported(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpGuardFilter> mcpGuard(AiApiProperties properties) {
        var registration = new FilterRegistrationBean<>(
                new McpGuardFilter(new RateLimiter(properties.rateLimitPerMinute(), Clock.systemUTC())));
        registration.addUrlPatterns("/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
