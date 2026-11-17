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
package org.niord.core.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HtmlMail#fromHtml(String, String, HtmlMail.StyleHandling, boolean)} and the
 * DOM transforms it applies (element removal, image inlining, link absolutization, plain-text generation,
 * and inline-part de-duplication).
 *
 * <p>These are pure Jsoup-driven transforms with no DB or Quarkus involvement. The tests pin the
 * observable contract of the produced {@link HtmlMail}: its HTML text, its inline parts and its plain text.
 * Assertions use contains/!contains rather than exact document equality so they survive Jsoup re-serialization.
 *
 * <p>Only resources on the base URI's origin are fetched and inlined; where a test needs to see what was
 * requested, two {@link HttpStub}s on different ports play the application's own origin and another host.
 */
public class HtmlMailTest {

    private static final String BASE_URI = "http://example.com/dir/";

    // ------------------------------------------------------------------
    // Element removal
    // ------------------------------------------------------------------

    @Test
    public void scriptElementsAreRemoved() throws IOException {
        String html = "<html><head><script>alert('x');</script></head>"
                + "<body><p>Body text</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        // The <script> element and its content must be stripped, but body text survives.
        assertFalse(mail.getHtmlText().contains("<script"), "script tag should have been removed");
        assertFalse(mail.getHtmlText().contains("alert("), "script content should have been removed");
        assertTrue(mail.getHtmlText().contains("Body text"), "body text should survive");
    }

    @Test
    public void metaBaseAndIframeElementsAreRemoved() throws IOException {
        String html = "<html><head>"
                + "<meta charset=\"utf-8\">"
                + "<base href=\"http://other.example.com/\">"
                + "</head><body>"
                + "<iframe src=\"http://evil.example.com/frame.html\"></iframe>"
                + "<p>Visible</p>"
                + "</body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        String out = mail.getHtmlText();
        assertFalse(out.contains("<meta"), "meta should be removed");
        assertFalse(out.contains("<base"), "base should be removed");
        assertFalse(out.contains("<iframe"), "iframe should be removed");
        assertTrue(out.contains("Visible"), "regular content should remain");
    }

    // ------------------------------------------------------------------
    // Image inlining
    // ------------------------------------------------------------------

    @Test
    public void relativeImageIsRewrittenToCidAndAbsolutized() throws IOException {
        String html = "<html><body><img src=\"pic.png\"></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        // The img src is rewritten to a cid reference using the "img" prefix and the running index (img0).
        assertTrue(mail.getHtmlText().contains("cid:img"), "img src should be rewritten to a cid");
        assertFalse(mail.getHtmlText().contains("src=\"pic.png\""),
                "the original relative src should no longer be present as a real src");

        List<InlineMailPart> parts = mail.getInlineParts();
        assertEquals(1, parts.size(), "exactly one inline part expected");

        InlineMailPart part = parts.get(0);
        assertTrue(part.getContentId().startsWith("img"), "content id should start with the img prefix");
        assertEquals("img0", part.getContentId(), "first content id should be img0");
        assertEquals("http://example.com/dir/pic.png", part.getUrl(),
                "the url should be absolutized against the base uri");
    }

    @Test
    public void cidReferenceInHtmlMatchesInlinePartContentId() throws IOException {
        String html = "<html><body><img src=\"images/logo.gif\"></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        List<InlineMailPart> parts = mail.getInlineParts();
        assertEquals(1, parts.size());
        String cid = parts.get(0).getContentId();

        assertTrue(mail.getHtmlText().contains("cid:" + cid),
                "the html must reference the same cid used by the inline part");
        assertEquals("http://example.com/dir/images/logo.gif", parts.get(0).getUrl(),
                "relative path should be absolutized under the base uri");
    }

    @Test
    public void duplicateImageSrcProducesSingleInlinePart() throws IOException {
        // Two <img> with the SAME src must de-duplicate into a single inline part (same cid).
        String html = "<html><body>"
                + "<img src=\"pic.png\">"
                + "<img src=\"pic.png\">"
                + "</body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        List<InlineMailPart> parts = mail.getInlineParts();
        assertEquals(1, parts.size(), "the same url must collapse to one inline part");

        String cid = parts.get(0).getContentId();
        // Both images now reference the same cid.
        int firstIdx = mail.getHtmlText().indexOf("cid:" + cid);
        int lastIdx = mail.getHtmlText().lastIndexOf("cid:" + cid);
        assertTrue(firstIdx >= 0, "first image should reference the cid");
        assertTrue(lastIdx > firstIdx, "there should be two distinct references to the same cid");
        assertEquals("http://example.com/dir/pic.png", parts.get(0).getUrl());
    }

    // ------------------------------------------------------------------
    // Link absolutization
    // ------------------------------------------------------------------

    @Test
    public void relativeAnchorHrefIsAbsolutized() throws IOException {
        String html = "<html><body><a href=\"page.html\">Link</a></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getHtmlText().contains("http://example.com/dir/page.html"),
                "relative anchor href should become absolute");
        assertFalse(mail.getHtmlText().contains("href=\"page.html\""),
                "the bare relative href should no longer be present");
    }

    @Test
    public void unresolvableAnchorHrefBecomesHash() throws IOException {
        // When there is no base URI, a relative/empty href cannot be absolutized (absUrl returns ""),
        // so absolutizeLinks disables the link by setting href="#". (NB: with a base URI present, an
        // empty href instead resolves to the base URI, so this branch needs an empty base.)
        String html = "<html><body><a href=\"\">Dead link</a></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, "", HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getHtmlText().contains("href=\"#\""),
                "unresolvable href should be replaced with #");
    }

    @Test
    public void formActionIsAbsolutized() throws IOException {
        String html = "<html><body><form action=\"submit\"></form></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getHtmlText().contains("http://example.com/dir/submit"),
                "form action should be absolutized");
    }

    // ------------------------------------------------------------------
    // Plain text inclusion
    // ------------------------------------------------------------------

    @Test
    public void plainTextIsGeneratedWhenRequested() throws IOException {
        String html = "<html><body><p>Hello plain world</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, true);

        assertNotNull(mail.getPlainText(), "plain text should be produced when includePlainText=true");
        assertTrue(mail.getPlainText().contains("Hello plain world"),
                "plain text should contain the visible body text");
        assertFalse(mail.getPlainText().contains("<p>"), "plain text should not contain html markup");
    }

    @Test
    public void plainTextIsNullWhenNotRequested() throws IOException {
        String html = "<html><body><p>No plain text here</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertNull(mail.getPlainText(), "plain text should be null when includePlainText=false");
        // HTML text must still be produced regardless of the plain-text flag.
        assertTrue(mail.getHtmlText().contains("No plain text here"));
    }

    // ------------------------------------------------------------------
    // Style handling
    // ------------------------------------------------------------------

    @Test
    public void inlineStylesRemovesStyleBlock() throws IOException {
        // The INLINE_STYLES path parses <style> blocks and removes them from the output.
        String html = "<html><head><style>p { color: red; }</style></head>"
                + "<body><p>Styled</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_STYLES, false);

        assertFalse(mail.getHtmlText().contains("<style"),
                "the <style> block should be removed under INLINE_STYLES");
        assertTrue(mail.getHtmlText().contains("Styled"), "the styled element should survive");
    }

    @Test
    public void inlineStylesPushesRuleOntoElementAsStyleAttribute() throws IOException {
        // Inlined rules should be merged onto the matching element's style attribute.
        String html = "<html><head><style>p { color: red; }</style></head>"
                + "<body><p>Styled</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_STYLES, false);

        assertTrue(mail.getHtmlText().contains("color: red") || mail.getHtmlText().contains("color:red"),
                "the parsed rule should be inlined onto the <p> as a style attribute");
    }

    @Test
    public void inlineCssDoesNotRemoveStyleBlock_documentsBehavior() throws IOException {
        // The brief expected <style> to be removed under INLINE_CSS, but processHtml() only calls
        // inlineStyleSheets() for that mode, which operates on <link> elements ONLY. A <style> block is
        // left completely untouched under INLINE_CSS. We assert the ACTUAL behavior here.
        String html = "<html><head><style>p { color: red; }</style></head>"
                + "<body><p>Styled</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getHtmlText().contains("<style"),
                "INLINE_CSS leaves inline <style> blocks in place (only <link> is inlined)");
        assertTrue(mail.getHtmlText().contains("color: red") || mail.getHtmlText().contains("color:red"),
                "the style rule text is retained verbatim");
    }

    @Test
    public void inlineCssRewritesStylesheetLinkToCid() throws IOException {
        // A <link rel="stylesheet"> is turned into a cid reference and recorded as an inline part.
        String html = "<html><head>"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\">"
                + "</head><body><p>Linked css</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        List<InlineMailPart> parts = mail.getInlineParts();
        assertEquals(1, parts.size(), "the stylesheet link should produce one inline part");
        assertTrue(parts.get(0).getContentId().startsWith("css"),
                "the css content id should use the css prefix");
        assertEquals("http://example.com/dir/style.css", parts.get(0).getUrl(),
                "the stylesheet url should be absolutized");
        assertTrue(mail.getHtmlText().contains("cid:" + parts.get(0).getContentId()),
                "the link href should reference the cid");
    }

    @Test
    public void inlineCssRemovesNonStylesheetLink() throws IOException {
        // A <link> that is not a stylesheet (e.g. rel=icon) is removed entirely under INLINE_CSS.
        String html = "<html><head>"
                + "<link rel=\"icon\" href=\"favicon.ico\">"
                + "</head><body><p>No icon</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertFalse(mail.getHtmlText().contains("<link"), "non-stylesheet link should be removed");
        assertTrue(mail.getInlineParts().isEmpty(),
                "no inline part should be recorded for a removed link");
        assertTrue(mail.getHtmlText().contains("No icon"));
    }

    // ------------------------------------------------------------------
    // Combined / no-op behavior
    // ------------------------------------------------------------------

    @Test
    public void documentWithoutLinkedResourcesHasNoInlineParts() throws IOException {
        String html = "<html><body><p>Just text</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty(),
                "a document with no images or stylesheets should have no inline parts");
        assertTrue(mail.getHtmlText().contains("Just text"));
    }

    // ------------------------------------------------------------------
    // Only the base URI's origin is fetched from
    // ------------------------------------------------------------------

    @Test
    public void anImageOnAnotherOriginIsNotInlinedButLeftForTheMailClient() throws IOException {
        String html = "<html><body>"
                + "<img src=\"https://other.example.net/pic.png\">"
                + "<img src=\"http://example.com:8080/dir/pic.png\">"
                + "<img src=\"http://169.254.169.254/latest/meta-data/\">"
                + "</body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty(), "nothing on another origin becomes a part the backend fetches");
        assertTrue(mail.getHtmlText().contains("src=\"https://other.example.net/pic.png\""));
        assertTrue(mail.getHtmlText().contains("src=\"http://example.com:8080/dir/pic.png\""));
        assertFalse(mail.getHtmlText().contains("cid:"));
    }

    @Test
    public void anImageOfAnyOtherKindIsDropped() throws IOException {
        String html = "<html><body><p>kept</p>"
                + "<img src=\"file:///etc/passwd\">"
                + "<img src=\"jar:file:/opt/app.jar!/application.yml\">"
                + "<img src=\"ftp://example.com/dir/pic.png\">"
                + "</body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty());
        assertFalse(mail.getHtmlText().contains("<img"), "the images are removed: " + mail.getHtmlText());
        assertTrue(mail.getHtmlText().contains("kept"));
    }

    @Test
    public void aDataImageStaysInPlace() throws IOException {
        String html = "<html><body><img src=\"data:image/png;base64,iVBORw0KGgo=\"></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty());
        assertTrue(mail.getHtmlText().contains("src=\"data:image/png;base64,iVBORw0KGgo=\""));
    }

    @Test
    public void withoutABaseUriNothingIsInlined() throws IOException {
        String html = "<html><body><img src=\"http://example.com/dir/pic.png\"></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, "", HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty());
    }

    @Test
    public void inlineCssDropsAStylesheetOnAnotherOrigin() throws IOException {
        String html = "<html><head>"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"https://other.example.net/style.css\">"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"file:///etc/passwd\">"
                + "</head><body><p>text</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty());
        assertFalse(mail.getHtmlText().contains("<link"));
    }

    @Test
    public void inlineStylesFetchesTheOwnStylesheetAndNothingElse() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondBytes("text/css", "p { color: red; }".getBytes(StandardCharsets.UTF_8));
            other.respondBytes("text/css", "p { color: blue; }".getBytes(StandardCharsets.UTF_8));
            String html = "<html><head>"
                    + "<link rel=\"stylesheet\" type=\"text/css\" href=\"/css/templates/mail.css\">"
                    + "<link rel=\"stylesheet\" type=\"text/css\" href=\"" + other.baseUrl() + "/style.css\">"
                    + "<link rel=\"stylesheet\" type=\"text/css\" href=\"file:///etc/passwd\">"
                    + "</head><body><p>Styled</p></body></html>";

            HtmlMail mail = HtmlMail.fromHtml(html, own.baseUrl(), HtmlMail.StyleHandling.INLINE_STYLES, false);

            assertEquals(1, own.requestCount());
            assertEquals("/css/templates/mail.css", own.lastRequest().path());
            assertEquals(0, other.requestCount(), "a stylesheet on another origin is never requested");
            assertTrue(mail.getHtmlText().contains("color: red") || mail.getHtmlText().contains("color:red"),
                    "the own stylesheet is inlined: " + mail.getHtmlText());
            assertFalse(mail.getHtmlText().contains("blue"));
            assertFalse(mail.getHtmlText().contains("<link"));
        }
    }

    @Test
    public void inlineStylesDoesNotFollowARedirectToAnotherOrigin() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondWith(StubResponse.redirect(302, other.baseUrl() + "/style.css"));
            other.respondBytes("text/css", "p { color: blue; }".getBytes(StandardCharsets.UTF_8));
            String html = "<html><head><link rel=\"stylesheet\" href=\"/css/templates/mail.css\"></head>"
                    + "<body><p>Styled</p></body></html>";

            // Like an unreachable stylesheet, it is left out of the mail (MAIL9), rather than being fetched from
            // where the redirect points
            HtmlMail mail = HtmlMail.fromHtml(html, own.baseUrl(), HtmlMail.StyleHandling.INLINE_STYLES, false);
            assertEquals(0, other.requestCount());
            assertFalse(mail.getHtmlText().contains("blue"));
            assertTrue(mail.getHtmlText().contains("Styled"));
        }
    }

    /**
     * MAIL9. An own stylesheet that cannot be fetched (nothing listens at the base URI, or it answers 404) costs the
     * mail its styling, not the mail: the mail is made without it, the next own stylesheet is still inlined, and the
     * failure is logged at WARN with the stylesheet's address. FAILS on the pre-fix code, which threw the fetch's
     * {@code IOException} out of the mail, so the scheduled mail was never sent.
     */
    @Test
    public void inlineStylesLeavesOutAnOwnStylesheetThatCannotBeFetched() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HtmlMail.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (HttpStub own = HttpStub.start()) {
            own.respond(request -> request.path().endsWith("/missing.css")
                    ? StubResponse.status(404)
                    : StubResponse.bytes("text/css", "p { color: red; }".getBytes(StandardCharsets.UTF_8)));
            String html = "<html><head>"
                    + "<link rel=\"stylesheet\" type=\"text/css\" href=\"/css/templates/missing.css\">"
                    + "<link rel=\"stylesheet\" type=\"text/css\" href=\"/css/templates/mail.css\">"
                    + "</head><body><p>Styled</p></body></html>";

            HtmlMail mail = HtmlMail.fromHtml(html, own.baseUrl(), HtmlMail.StyleHandling.INLINE_STYLES, false);

            assertTrue(mail.getHtmlText().contains("Styled"), mail.getHtmlText());
            assertTrue(mail.getHtmlText().contains("color: red") || mail.getHtmlText().contains("color:red"),
                    "the stylesheet that could be fetched is inlined: " + mail.getHtmlText());
            assertFalse(mail.getHtmlText().contains("<link"));
            List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), "one WARN: " + appender.list);
            assertTrue(warnings.get(0).getFormattedMessage().contains(own.baseUrl() + "/css/templates/missing.css"),
                    "the WARN names the stylesheet: " + warnings.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
        }

        // Nothing listens at the base URI: the mail is made without the stylesheet
        String unreachable = "http://127.0.0.1:" + freePort();
        HtmlMail mail = HtmlMail.fromHtml("<html><head><link rel=\"stylesheet\" href=\"/css/templates/mail.css\">"
                + "</head><body><p>Unstyled</p></body></html>", unreachable, HtmlMail.StyleHandling.INLINE_STYLES, false);
        assertTrue(mail.getHtmlText().contains("Unstyled"), mail.getHtmlText());
        assertFalse(mail.getHtmlText().contains("<link"));
    }

    /** A port nothing listens on: one the system just handed out and that was closed again */
    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    public void distinctImageUrlsProduceDistinctInlineParts() throws IOException {
        String html = "<html><body>"
                + "<img src=\"a.png\">"
                + "<img src=\"b.png\">"
                + "</body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, BASE_URI, HtmlMail.StyleHandling.INLINE_CSS, false);

        List<InlineMailPart> parts = mail.getInlineParts();
        assertEquals(2, parts.size(), "two distinct urls should produce two inline parts");
        // The two parts must carry distinct content ids.
        assertFalse(parts.get(0).getContentId().equals(parts.get(1).getContentId()),
                "the two inline parts must have distinct content ids");
    }
}
