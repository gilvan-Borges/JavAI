package br.com.gilvanborges.javai.admin.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Regras para aceitar o JWT do Cloudflare Access: assinatura (no decoder), validade, emissor, audiência
 * da aplicação no Access e e-mail do dono. Tudo precisa bater.
 */
public final class AccessJwtValidators {

    private AccessJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> of(AccessProperties access) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(access.issuer()),
                audience(access.audience()),
                ownerEmail(access.ownerEmail()));
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : failure("O token não é desta aplicação do Access.");
    }

    private static OAuth2TokenValidator<Jwt> ownerEmail(String expected) {
        return jwt -> expected.equalsIgnoreCase(jwt.getClaimAsString("email"))
                ? OAuth2TokenValidatorResult.success()
                : failure("Só o dono pode usar o admin.");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
