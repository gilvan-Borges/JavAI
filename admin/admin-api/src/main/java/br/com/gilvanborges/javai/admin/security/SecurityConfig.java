package br.com.gilvanborges.javai.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.HeaderBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * O Cloudflare Access fica na frente e só deixa passar o dono, com 2FA. Mesmo assim o app valida o JWT que o
 * Access manda em {@code Cf-Access-Jwt-Assertion}: se alguém chegar na origem sem passar pelo Access, recebe 401.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ACCESS_JWT_HEADER = "Cf-Access-Jwt-Assertion";

    @Bean
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(new HeaderBearerTokenResolver(ACCESS_JWT_HEADER))
                        .jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // O CSRF do Spring depende de sessão; aqui a proteção é o CrossSiteRequestFilter.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .addFilterBefore(new CrossSiteRequestFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder accessJwtDecoder(AccessProperties access) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(access.jwksUri()).build();
        decoder.setJwtValidator(AccessJwtValidators.of(access));
        return decoder;
    }
}
