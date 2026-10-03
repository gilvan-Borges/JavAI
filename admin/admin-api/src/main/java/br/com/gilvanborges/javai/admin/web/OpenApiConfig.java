package br.com.gilvanborges.javai.admin.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI adminOpenApi() {
        return new OpenAPI().info(new Info()
                .title("JavAI · Admin")
                .version("v1")
                .description("""
                        CRUD do conteúdo, publicação e auditoria. Só o dono acessa: o Cloudflare Access autentica \
                        e o app valida o JWT do cabeçalho Cf-Access-Jwt-Assertion. Toda escrita vai para a auditoria."""));
    }
}
