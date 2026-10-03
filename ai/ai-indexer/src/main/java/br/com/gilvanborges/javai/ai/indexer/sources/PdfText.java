package br.com.gilvanborges.javai.ai.indexer.sources;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/** Extrai o texto de um PDF público (o currículo, por exemplo). Só texto: nada é executado e nenhum link é seguido. */
final class PdfText {

    private static final int MAX_PAGES = 40;

    private PdfText() {
    }

    static boolean isPdf(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    static String extract(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            var stripper = new PDFTextStripper();
            stripper.setEndPage(MAX_PAGES);
            return stripper.getText(document);
        }
    }
}
