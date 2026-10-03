package br.com.gilvanborges.javai.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Quem fez a alteração: o e-mail do JWT do Access, que vai para {@code updated_by} e para a auditoria. */
public final class Actor {

    private Actor() {
    }

    public static String of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String email = token.getToken().getClaimAsString("email");
            if (email != null) {
                return email;
            }
        }
        throw new IllegalStateException("Requisição autenticada sem e-mail do Access.");
    }
}
