/*
 * Copyright 2016 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.script.pdf;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.ImageRenderInfo;
import com.itextpdf.text.pdf.parser.PdfReaderContentParser;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.itextpdf.text.pdf.parser.RenderListener;
import com.itextpdf.text.pdf.parser.TextRenderInfo;
import com.itextpdf.text.pdf.parser.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-neutral Tier-1 unit tests for {@link HtmlToPdfRenderer}.
 * <p>
 * {@code HtmlToPdfRenderer} turns HTML into a real PDF using flying-saucer (the
 * {@code org.xhtmlrenderer.*} package) and openPDF, both of which are on the classpath. These tests
 * exercise the public builder API and {@link HtmlToPdfRenderer#render()} end-to-end against a
 * {@link ByteArrayOutputStream} (and a temp {@link File}) sink, then assert that the produced bytes are
 * a structurally valid PDF by checking for the {@code "%PDF"} magic-number header. They also cover the
 * builder validation contract ("No HTML defined" / "No PDF output defined"), SVG embedding, the
 * namespaced-tag clean-up path, the {@code html(InputStream)} classpath overload, and PDF encryption.
 * <p>
 * The renderer loads images and style sheets from the base URI's origin only ({@link SameOriginUserAgent}):
 * two {@link HttpStub}s on different ports play the backend's own origin and another host, and the tests
 * assert which of them was asked for what.
 * <p>
 * A document that shows a map image carries the map credit on every page, beside the footer that pdf.css
 * places; the tests read the text and where it is drawn back out of the PDF with iText's parser.
 * <p>
 * Unlike the prior assertion-free {@code SvgReportTest} (which merely rendered a PDF to disk without ever
 * checking the result), every test here makes a strong assertion on the produced PDF bytes, so the tests
 * actually validate that real, non-empty PDF output was generated.
 * <p>
 * The renderer and its builder are plain library code with no Quarkus, database or DI dependency, so
 * these tests survive a later Quarkus-to-Spring migration unchanged.
 */
public class HtmlToPdfRendererTest {

    /** Asserts that the given bytes are a non-empty PDF, i.e. they start with the ASCII magic number "%PDF". */
    private void assertIsPdf(byte[] b) {
        Assertions.assertTrue(b.length > 0, "PDF output must be non-empty");
        Assertions.assertTrue(b.length >= 4, "PDF output must contain the 4-byte '%PDF' header");
        Assertions.assertEquals('%', b[0]);
        Assertions.assertEquals('P', b[1]);
        Assertions.assertEquals('D', b[2]);
        Assertions.assertEquals('F', b[3]);
    }

    @Test
    public void rendersSimpleHtmlToValidPdf() throws Exception {
        // A basic HTML document renders to bytes carrying the "%PDF" header.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .html("<html><body><h1>Hi</h1></body></html>")
                .pdf(out)
                .build()
                .render();
        assertIsPdf(out.toByteArray());
    }

    @Test
    public void rendersInlineSvgToValidPdfWithoutCrashing() throws Exception {
        // Embedding an inline SVG (with the required svg{display:inline-block} style) yields a valid, non-empty PDF.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String html = "<html><head><style>svg{display:inline-block;}</style></head><body>"
                + "<svg width=\"100\" height=\"100\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"yellow\"></circle></svg>"
                + "</body></html>";
        HtmlToPdfRenderer.newBuilder()
                .html(html)
                .pdf(out)
                .build()
                .render();
        byte[] bytes = out.toByteArray();
        Assertions.assertTrue(bytes.length > 0, "SVG PDF output must be non-empty");
        assertIsPdf(bytes);
    }

    @Test
    public void rendersHtmlFromInputStreamClasspathResourceToValidPdf() throws Exception {
        // The html(InputStream) overload reads a classpath resource (/svg.html) and renders it to a valid PDF.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .html(getClass().getResourceAsStream("/svg.html"))
                .pdf(out)
                .build()
                .render();
        assertIsPdf(out.toByteArray());
    }

    @Test
    public void stripsNamespacedTagsAndRendersValidPdf() throws Exception {
        // cleanUpHtml() removes namespaced tags like "<o:p>...</o:p>", so the document still renders to a valid PDF.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .html("<html><body><p>before<o:p>junk</o:p>after</p></body></html>")
                .pdf(out)
                .build()
                .render();
        assertIsPdf(out.toByteArray());
    }

    @Test
    public void encryptedPdfStillCarriesPdfHeaderAndIsNonEmpty() throws Exception {
        // encrypt("pw") produces an encrypted PDF that still starts with the "%PDF" header and is non-empty.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .html("<html><body>secret</body></html>")
                .encrypt("pw")
                .pdf(out)
                .build()
                .render();
        byte[] bytes = out.toByteArray();
        Assertions.assertTrue(bytes.length > 0, "Encrypted PDF output must be non-empty");
        assertIsPdf(bytes);
    }

    @Test
    public void pdfFileOverloadWritesValidPdfFile() throws Exception {
        // The pdf(File) overload writes the rendered PDF to disk: the file is non-empty and begins with "%PDF".
        File tmp = File.createTempFile("htmlpdf", ".pdf");
        tmp.deleteOnExit();
        HtmlToPdfRenderer.newBuilder()
                .html("<html><body><h1>File</h1></body></html>")
                .pdf(tmp)
                .build()
                .render();
        Assertions.assertTrue(tmp.length() > 0, "PDF file must be non-empty");
        byte[] header = new byte[4];
        try (FileInputStream in = new FileInputStream(tmp)) {
            int read = in.read(header);
            Assertions.assertEquals(4, read, "Expected to read 4 header bytes");
        }
        Assertions.assertEquals('%', header[0]);
        Assertions.assertEquals('P', header[1]);
        Assertions.assertEquals('D', header[2]);
        Assertions.assertEquals('F', header[3]);
    }

    // ------------------------------------------------------------------
    // Resources are loaded from the base URI's origin only
    // ------------------------------------------------------------------

    /** A 2x2 PNG, so an image the renderer does load decodes */
    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Renders the HTML against the base URI and asserts that a PDF came out */
    private void render(String html, String baseUri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .baseUri(baseUri)
                .html(html)
                .pdf(out)
                .build()
                .render();
        assertIsPdf(out.toByteArray());
    }

    @Test
    public void rootRelativeImagesAndStyleSheetsAreLoadedFromTheBaseUri() throws Exception {
        byte[] png = png();
        try (HttpStub own = HttpStub.start()) {
            own.respond(req -> req.path().endsWith(".css")
                    ? StubResponse.bytes("text/css", "p { color: red; }".getBytes(StandardCharsets.UTF_8))
                    : StubResponse.bytes("image/png", png));

            render("<html><head><link rel=\"stylesheet\" type=\"text/css\" href=\"/css/templates/pdf.css\"/></head>"
                    + "<body><img src=\"/img/company-logo.png\"/></body></html>", own.baseUrl());

            Assertions.assertNotNull(own.firstRequestForPath("/css/templates/pdf.css"), "the style sheet is loaded");
            Assertions.assertNotNull(own.firstRequestForPath("/img/company-logo.png"), "the image is loaded");
        }
    }

    @Test
    public void resourcesOnAnotherOriginAreNotLoadedAndThePdfStillRenders() throws Exception {
        byte[] png = png();
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondBytes("image/png", png);
            other.respondBytes("image/png", png);

            render("<html><head><link rel=\"stylesheet\" type=\"text/css\" href=\"" + other.baseUrl() + "/x.css\"/></head>"
                    + "<body><p>text</p><img src=\"" + other.baseUrl() + "/internal.png\"/>"
                    + "<img src=\"file:///etc/hosts\"/></body></html>", own.baseUrl());

            Assertions.assertEquals(0, other.requestCount(), "nothing is requested from another origin");
        }
    }

    @Test
    public void aRedirectToAnotherOriginIsNotFollowed() throws Exception {
        byte[] png = png();
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondWith(StubResponse.redirect(302, other.baseUrl() + "/internal.png"));
            other.respondBytes("image/png", png);

            render("<html><body><img src=\"/rest/message-map-image/1.png\"/></body></html>", own.baseUrl());

            Assertions.assertNotNull(own.firstRequestForPath("/rest/message-map-image/1.png"));
            Assertions.assertEquals(0, other.requestCount(), "the redirect off the origin is not followed");
        }
    }

    @Test
    public void aRedirectWithinTheOriginIsFollowed() throws Exception {
        byte[] png = png();
        try (HttpStub own = HttpStub.start()) {
            // The backend's own thumbnail endpoints redirect to file-type icons under /img
            own.respond(req -> req.path().startsWith("/rest/")
                    ? StubResponse.redirect(302, "/img/filetypes/pdf.png")
                    : StubResponse.bytes("image/png", png));

            render("<html><body><img src=\"/rest/repo/thumb/messages/1/a.pdf\"/></body></html>", own.baseUrl());

            Assertions.assertNotNull(own.firstRequestForPath("/img/filetypes/pdf.png"),
                    "the icon the redirect names is loaded");
        }
    }

    @Test
    public void anExternalImageInsideInlineSvgIsNotLoaded() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            other.respondBytes("image/png", png());

            render("<html><head><style>svg{display:inline-block;}</style></head><body>"
                    + "<svg width=\"100\" height=\"100\" xmlns=\"http://www.w3.org/2000/svg\""
                    + " xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                    + "<image x=\"0\" y=\"0\" width=\"10\" height=\"10\" xlink:href=\"" + other.baseUrl() + "/svg.png\""
                    + " href=\"" + other.baseUrl() + "/svg.png\"></image></svg>"
                    + "</body></html>", own.baseUrl());

            Assertions.assertEquals(0, other.requestCount(), "the SVG renderer loads nothing from another origin");
        }
    }

    @Test
    public void withoutABaseUriNothingIsLoaded() throws Exception {
        try (HttpStub other = HttpStub.start()) {
            other.respondBytes("image/png", png());

            render("<html><body><img src=\"" + other.baseUrl() + "/x.png\"/><img src=\"relative.png\"/></body></html>", null);

            Assertions.assertEquals(0, other.requestCount());
        }
    }

    // ------------------------------------------------------------------
    // The map credit in the page footer
    // ------------------------------------------------------------------

    private static final String MAP_IMAGE_ADDRESS = "/rest/message-map-image/";
    private static final String CREDIT = "© OpenStreetMap contributors";

    /** The page style of a message report: pdf.css, plus the page size a report template sets */
    private static final String REPORT_HEAD = "<head>"
            + "<link rel=\"stylesheet\" type=\"text/css\" href=\"/css/templates/pdf.css\"/>"
            + "<style type=\"text/css\">@page { size: A4 portrait; }</style>"
            + "</head>";

    /** A footer with a line of text, as the country footers have, placed by pdf.css in the bottom-centre box */
    private static final String FOOTER = "<div class=\"footer\"><div style=\"font-size: 8px\">Footer line</div></div>";

    /** A two-page report body whose second page shows the given image */
    private static String twoPagesShowing(String imageSrc) {
        return "<html>" + REPORT_HEAD + "<body>" + FOOTER
                + "<p>First page</p><div style=\"page-break-after: always\"></div>"
                + "<p>Second page</p><img src=\"" + imageSrc + "\" width=\"120\" height=\"120\"/>"
                + "</body></html>";
    }

    /** Serves pdf.css from the class path and a PNG for every other address */
    private static HttpStub ownOrigin() throws Exception {
        byte[] png = png();
        byte[] pdfCss;
        try (var in = HtmlToPdfRendererTest.class.getResourceAsStream("/META-INF/resources/css/templates/pdf.css")) {
            Assertions.assertNotNull(in, "pdf.css is on the class path");
            pdfCss = in.readAllBytes();
        }
        HttpStub own = HttpStub.start();
        own.respond(req -> req.path().endsWith(".css")
                ? StubResponse.bytes("text/css", pdfCss)
                : StubResponse.bytes("image/png", png));
        return own;
    }

    /** Renders the HTML with the given map credit and returns the PDF */
    private byte[] renderWithMapCredit(String html, String baseUri, String credit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlToPdfRenderer.newBuilder()
                .baseUri(baseUri)
                .html(html)
                .mapCredit(MAP_IMAGE_ADDRESS, credit)
                .pdf(out)
                .build()
                .render();
        assertIsPdf(out.toByteArray());
        return out.toByteArray();
    }

    /** Returns the text of every page of the PDF */
    private static List<String> pageTexts(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(PdfTextExtractor.getTextFromPage(reader, page));
            }
            return pages;
        } finally {
            reader.close();
        }
    }

    /** Where a run of text is drawn on a page, in points from the bottom-left corner */
    private record TextRun(String text, float left, float right, float baseline) {
    }

    /** Returns the runs of text drawn on the first page of the PDF, and its width */
    private static List<TextRun> firstPageRuns(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            List<TextRun> runs = new ArrayList<>();
            new PdfReaderContentParser(reader).processContent(1, new RenderListener() {
                @Override public void beginTextBlock() { }
                @Override public void endTextBlock() { }
                @Override public void renderImage(ImageRenderInfo info) { }
                @Override public void renderText(TextRenderInfo info) {
                    runs.add(new TextRun(info.getText(),
                            info.getBaseline().getStartPoint().get(Vector.I1),
                            info.getBaseline().getEndPoint().get(Vector.I1),
                            info.getBaseline().getStartPoint().get(Vector.I2)));
                }
            });
            return runs;
        } finally {
            reader.close();
        }
    }

    private static TextRun run(List<TextRun> runs, String text) {
        return runs.stream().filter(r -> r.text().contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no text '" + text + "' in " + runs));
    }

    @Test
    public void aPdfShowingAMapImageCarriesTheCreditOnEveryPage() throws Exception {
        try (HttpStub own = ownOrigin()) {
            byte[] pdf = renderWithMapCredit(twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png"), own.baseUrl(), CREDIT);

            Assertions.assertNotNull(own.firstRequestForPath(MAP_IMAGE_ADDRESS + "1.png"), "the map image is loaded");
            List<String> pages = pageTexts(pdf);
            Assertions.assertEquals(2, pages.size());
            for (String page : pages) {
                Assertions.assertTrue(page.contains(CREDIT), "every page carries the credit: " + page);
            }
        }
    }

    @Test
    public void aPdfWithoutAMapImageCarriesNoCredit() throws Exception {
        try (HttpStub own = ownOrigin()) {
            byte[] pdf = renderWithMapCredit(twoPagesShowing("/img/company-logo.png"), own.baseUrl(), CREDIT);

            Assertions.assertNotNull(own.firstRequestForPath("/img/company-logo.png"), "the other image is loaded");
            List<String> pages = pageTexts(pdf);
            Assertions.assertEquals(2, pages.size());
            for (String page : pages) {
                Assertions.assertFalse(page.contains("OpenStreetMap"), "no page carries a credit: " + page);
            }
        }
    }

    @Test
    public void withoutACreditTheMapImageIsRenderedAsItIs() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String html = twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png");

            ByteArrayOutputStream unset = new ByteArrayOutputStream();
            HtmlToPdfRenderer.newBuilder().baseUri(own.baseUrl()).html(html).pdf(unset).build().render();
            byte[] blank = renderWithMapCredit(html, own.baseUrl(), " ");

            for (byte[] pdf : List.of(unset.toByteArray(), blank)) {
                for (String page : pageTexts(pdf)) {
                    Assertions.assertTrue(page.contains("Footer line"), page);
                    Assertions.assertFalse(page.contains("OpenStreetMap"), page);
                }
            }
        }
    }

    @Test
    public void theCreditStandsBesideTheFooterAndLeavesItInPlace() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String html = twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png");

            List<TextRun> without = firstPageRuns(renderWithMapCredit(html, own.baseUrl(), null));
            List<TextRun> with = firstPageRuns(renderWithMapCredit(html, own.baseUrl(), CREDIT));

            // The footer is drawn where it is drawn without the credit (to within layout rounding)
            TextRun before = run(without, "Footer line");
            TextRun footer = run(with, "Footer line");
            Assertions.assertEquals(before.left(), footer.left(), 0.1, "the footer keeps its place: " + footer);
            Assertions.assertEquals(before.right(), footer.right(), 0.1, "the footer keeps its place: " + footer);
            Assertions.assertEquals(before.baseline(), footer.baseline(), 0.1, "the footer keeps its place: " + footer);
            // The credit is on the right of it, level with its first line rather than at the paper edge
            TextRun credit = run(with, "OpenStreetMap");
            Assertions.assertEquals(CREDIT, credit.text());
            Assertions.assertTrue(credit.left() > footer.right(), "the credit is right of the footer: " + credit);
            Assertions.assertEquals(footer.baseline(), credit.baseline(), 1, "the credit is level with the footer: " + credit);
            Assertions.assertTrue(credit.baseline() > 28, "the credit is at least 1 cm above the paper edge: " + credit);
        }
    }

    @Test
    public void aTemplateThatFillsTheBottomRightBoxGetsTheCreditOnTheLeft() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String html = twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png").replace("</head>",
                    "<style type=\"text/css\">@page { @bottom-right { content: \"Printed by the template\"; } }</style></head>");

            List<TextRun> runs = firstPageRuns(renderWithMapCredit(html, own.baseUrl(), CREDIT));

            TextRun template = run(runs, "Printed by the template");
            TextRun credit = run(runs, "OpenStreetMap");
            Assertions.assertTrue(credit.right() < run(runs, "Footer line").left(), "the credit is left of the footer: " + credit);
            Assertions.assertTrue(template.left() > run(runs, "Footer line").right(), "the template keeps its box: " + template);
        }
    }

    @Test
    public void aTemplateThatFillsBothBoxesBesideTheFooterGetsTheCreditOnTheRight() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String html = twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png").replace("</head>",
                    "<style type=\"text/css\">@page { @bottom-left { content: \"Left note\"; }"
                            + " @bottom-right { content: \"Right note\"; } }</style></head>");

            List<TextRun> runs = firstPageRuns(renderWithMapCredit(html, own.baseUrl(), CREDIT));

            // The credit is a condition of the map tiles' licence, so it takes the right box over the template
            Assertions.assertTrue(run(runs, "OpenStreetMap").left() > run(runs, "Footer line").right());
            Assertions.assertNotNull(run(runs, "Left note"));
            Assertions.assertTrue(runs.stream().noneMatch(r -> r.text().contains("Right note")), runs.toString());
        }
    }

    @Test
    public void aCornerBoxIsNotTheBoxBesideTheFooter() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String html = twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png").replace("</head>",
                    "<style type=\"text/css\">@page { @bottom-right-corner { content: \"x\"; } }</style></head>");

            List<TextRun> runs = firstPageRuns(renderWithMapCredit(html, own.baseUrl(), CREDIT));

            Assertions.assertTrue(run(runs, "OpenStreetMap").left() > run(runs, "Footer line").right());
        }
    }

    @Test
    public void theCreditIsPrintedAsTextWhateverItContains() throws Exception {
        try (HttpStub own = ownOrigin()) {
            String credit = "Tiles \"A\" \\ B } </style> <b>C</b>";

            List<String> pages = pageTexts(renderWithMapCredit(twoPagesShowing(MAP_IMAGE_ADDRESS + "1.png"), own.baseUrl(), credit));

            for (String page : pages) {
                Assertions.assertTrue(page.contains(credit), "the credit is printed as written: " + page);
            }
        }
    }

    @Test
    public void buildWithoutHtmlThrowsIllegalArgumentException() {
        // build() with no HTML set rejects the configuration with IllegalArgumentException("No HTML defined").
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            HtmlToPdfRenderer.newBuilder()
                    .pdf(out)
                    .build();
            Assertions.fail("Expected IllegalArgumentException when no HTML is defined");
        } catch (IllegalArgumentException e) {
            Assertions.assertEquals("No HTML defined", e.getMessage());
        }
    }

    @Test
    public void buildWithoutPdfOutputThrowsIllegalArgumentException() {
        // build() with HTML but no PDF output rejects the configuration with IllegalArgumentException("No PDF output defined").
        try {
            HtmlToPdfRenderer.newBuilder()
                    .html("<html><body>x</body></html>")
                    .build();
            Assertions.fail("Expected IllegalArgumentException when no PDF output is defined");
        } catch (IllegalArgumentException e) {
            Assertions.assertEquals("No PDF output defined", e.getMessage());
        }
    }
}
