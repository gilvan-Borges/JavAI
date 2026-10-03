package br.com.gilvanborges.javai.admin.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI adminOpenApi() {
        // O app roda atrás do Caddy e da Cloudflare e só enxerga HTTP. Sem isto o springdoc monta as chamadas do
        // "Try it out" com http://, e o navegador as bloqueia (conteúdo misto). A URL relativa herda o https da página.
        return new OpenAPI().addServersItem(new Server().url("/").description("Este servidor")).info(new Info()
                .title("JavAI · Admin")
                .version("v1")
                .description("""
                        CRUD do conteúdo, publicação e auditoria. Só o dono acessa: o Cloudflare Access autentica \
                        e o app valida o JWT do cabeçalho Cf-Access-Jwt-Assertion. Toda escrita vai para a auditoria."""));
    }
}
