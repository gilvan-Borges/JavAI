package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.security.SecurityConfig;
import br.com.gilvanborges.javai.admin.security.TestJwtDecoderConfig;
import br.com.gilvanborges.javai.admin.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/** A tela de FAQ, com a segurança real: só abre com o token do Access e não carrega script inline. */
@WebMvcTest(FaqPage.class)
@Import({SecurityConfig.class, TestJwtDecoderConfig.class})
@TestPropertySource(properties = {
        "admin.access.jwks-uri=https://teste.cloudflareaccess.com/cdn-cgi/access/certs",
        "admin.access.issuer=https://teste.cloudflareaccess.com",
        "admin.access.audience=aud-de-teste",
        "admin.access.owner-email=gilvan@example.com",
})
class FaqPageTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void so_abre_com_o_token_do_access() {
        assertThat(mvc.get().uri("/admin/faqs")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/admin/faqs.js")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_pagina_tem_o_formulario_o_script_do_mesmo_dominio_e_nenhum_inline() throws Exception {
        var page = mvc.get().uri("/admin/faqs").header(TestTokens.HEADER, TestTokens.owner()).exchange();

        assertThat(page).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("id=\"form\"").contains("<script src=\"/admin/faqs.js\">")
                .doesNotContain("onclick=").doesNotContain("<script>");
    }

    @Test
    void o_script_escreve_por_textcontent_e_aceita_a_pergunta_vinda_do_treinar() throws Exception {
        var script = mvc.get().uri("/admin/faqs.js").header(TestTokens.HEADER, TestTokens.owner()).exchange();

        assertThat(script).hasStatusOk();
        String js = script.getResponse().getContentAsString();
        assertThat(js).contains("textContent").contains("URLSearchParams").doesNotContain("innerHTML")
                .doesNotContain("eval(").doesNotContain("document.write");
    }
}
