package br.com.gilvanborges.javai.admin.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Troca só a origem das chaves (a de teste, no lugar do JWKS do Access). As regras de validação são as reais. */
@TestConfiguration
@EnableConfigurationProperties(AccessProperties.class)
public class TestJwtDecoderConfig {

    @Bean
    @Primary
    JwtDecoder testAccessJwtDecoder(AccessProperties access) {
        var decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey()).build();
        decoder.setJwtValidator(AccessJwtValidators.of(access));
        return decoder;
    }
}
