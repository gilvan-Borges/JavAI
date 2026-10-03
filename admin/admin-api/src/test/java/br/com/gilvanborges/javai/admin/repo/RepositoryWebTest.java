package br.com.gilvanborges.javai.admin.repo;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.security.SecurityConfig;
import br.com.gilvanborges.javai.admin.security.TestJwtDecoderConfig;
import br.com.gilvanborges.javai.admin.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A tela e a API de repositórios com a segurança real do admin; GitHub e banco são simulados. */
@WebMvcTest({RepositoryController.class, RepositoryPage.class})
@Import({SecurityConfig.class, TestJwtDecoderConfig.class, RepositoryWebTest.NoTransactions.class})
@TestPropertySource(properties = {
        "admin.access.jwks-uri=https://teste.cloudflareaccess.com/cdn-cgi/access/certs",
        "admin.access.issuer=https://teste.cloudflareaccess.com",
        "admin.access.audience=aud-de-teste",
        "admin.access.owner-email=gilvan@example.com",
})
class RepositoryWebTest {

    private static final String BODY = """
            {"fullName":"gilvan-Borges/JavAI","kind":"DESTAQUE","notePt":"A IA do portfólio.","noteEn":"The portfolio AI.","enabled":true}""";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    RepositoryStore store;

    @MockitoBean
    GitHubLookup github;

    @MockitoBean
    AuditLog audit;

    @TestConfiguration
    static class NoTransactions {
        @Bean
        TransactionTemplate transactionTemplate() {
            return new TransactionTemplate(mock(PlatformTransactionManager.class));
        }
    }

    private static RepositoryStore.Row row(boolean fork) {
        return new RepositoryStore.Row("gilvan-Borges/JavAI", true, "destaque", "A IA do portfólio.", "The portfolio AI.",
                fork, "main", 900, OffsetDateTime.parse("2026-10-03T17:00:00Z"), TestTokens.OWNER);
    }

    // --- a tela --------------------------------------------------------------------------------------------

    @Test
    void a_tela_so_abre_com_o_token_do_access() {
        assertThat(mvc.get().uri("/admin/repos")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/admin/repos.js")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_tela_e_um_html_com_formulario_e_script_do_mesmo_dominio_sem_inline() throws Exception {
        var page = mvc.get().uri("/admin/repos").header(TestTokens.HEADER, TestTokens.owner()).exchange();

        assertThat(page).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("id=\"form\"").contains("gilvan-Borges/JavAI").contains("<script src=\"/admin/repos.js\">");
        // O CSP do Caddy é default-src 'self': nada de script inline nem de handler no HTML.
        assertThat(html).doesNotContain("onclick=").doesNotContain("<script>");
    }

    @Test
    void o_script_so_escreve_na_pagina_por_textcontent_nunca_por_innerhtml() throws Exception {
        var script = mvc.get().uri("/admin/repos.js").header(TestTokens.HEADER, TestTokens.owner()).exchange();

        assertThat(script).hasStatusOk();
        String js = script.getResponse().getContentAsString();
        assertThat(js).contains("textContent").doesNotContain("innerHTML").doesNotContain("eval(").doesNotContain("document.write");
    }

    // --- a API ---------------------------------------------------------------------------------------------

    @Test
    void liga_um_repositorio_publico_buscando_os_metadados_no_github_e_audita() {
        when(github.fetch("gilvan-Borges/JavAI")).thenReturn(Optional.of(
                new GitHubLookup.Meta("gilvan-Borges/JavAI", false, "main", 900)));
        when(store.find("gilvan-Borges/JavAI")).thenReturn(Optional.empty());
        when(store.upsert(any(), any(), eq(TestTokens.OWNER))).thenReturn(row(false));

        var response = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(BODY);

        assertThat(response).hasStatusOk();
        verify(audit).record(eq(TestTokens.OWNER), eq(AuditAction.REPO), eq("github_repo"), eq("gilvan-Borges/JavAI"),
                isNull(), any());
    }

    @Test
    void repositorio_privado_ou_inexistente_e_recusado_sem_gravar() {
        when(github.fetch("gilvan-Borges/JavAI")).thenReturn(Optional.empty());

        var response = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(BODY);

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(store, never()).upsert(any(), any(), any());
    }

    @Test
    void fork_nao_pode_ser_ligado() {
        when(github.fetch("gilvan-Borges/JavAI")).thenReturn(Optional.of(
                new GitHubLookup.Meta("gilvan-Borges/JavAI", true, "main", 900)));

        var response = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(BODY);

        assertThat(response).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(store, never()).upsert(any(), any(), any());
    }

    @Test
    void nome_fora_do_formato_dono_nome_nao_chega_ao_github() {
        var response = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("gilvan-Borges/JavAI", "../etc/passwd"));

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        verify(github, never()).fetch(any());
    }

    @Test
    void escrita_de_outro_site_ou_sem_json_e_recusada_pelo_filtro_csrf() {
        var crossSite = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .header("Sec-Fetch-Site", "cross-site").contentType(MediaType.APPLICATION_JSON).content(BODY);
        var notJson = mvc.put().uri("/admin/v1/repositories").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.TEXT_PLAIN).content(BODY);

        assertThat(crossSite).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(notJson).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void remover_audita_e_apaga() {
        when(store.find("gilvan-Borges/JavAI")).thenReturn(Optional.of(row(false)));

        var response = mvc.delete().uri("/admin/v1/repositories/gilvan-Borges/JavAI")
                .header(TestTokens.HEADER, TestTokens.owner());

        assertThat(response).hasStatus(HttpStatus.NO_CONTENT);
        verify(store).delete("gilvan-Borges/JavAI");
        verify(audit).record(eq(TestTokens.OWNER), eq(AuditAction.REPO), eq("github_repo"), eq("gilvan-Borges/JavAI"),
                any(), isNull());
    }
}
