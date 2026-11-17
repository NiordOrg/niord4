/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.springframework.beans.factory.annotation.Autowired;
import org.infinispan.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.http.HttpStub;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the URL-fetching + caching collaboration behind HTML mail building:
 * {@link CachedUrlDataSource} (the JavaMail {@link jakarta.activation.DataSource} that fetches a URL
 * once and caches it in the shared {@link MailAttachmentCache}) and {@link HtmlMail#fromHtml} (which
 * rewrites {@code <img src>} references into inline {@code cid:} mail parts).
 *
 * <p><b>Why this is an integration test.</b> Two things require real infrastructure: (1) the
 * {@link MailAttachmentCache} is an {@code @ApplicationScoped} Infinispan-backed CDI bean, so the test
 * injects it exactly as the application does; and (2) {@link CachedUrlDataSource} performs a genuine
 * outbound {@code HTTP GET} via {@link URL#openConnection()}. To exercise (and assert against) that
 * fetch deterministically — including the no-network-dependency and "fetch only once" guarantees — the
 * test stands up a real, in-process HTTP endpoint with the dependency-free {@link HttpStub} fixture
 * (built on the JDK {@code com.sun.net.httpserver.HttpServer}) and points the code at
 * {@link HttpStub#baseUrl()}. The stub records every request, so the cache's "single fetch" contract is
 * verified against {@link HttpStub#requestCount()} rather than merely assumed.
 *
 * <p><b>Cache wiring.</b> The {@link MailAttachmentCache} starts a single shared in-memory
 * {@code LOCAL} cache for the whole Quarkus test JVM; it is NOT isolated between tests by a transaction.
 * To keep tests order-independent every test uses a UNIQUE stub-derived URL (the stub binds an ephemeral
 * port per test, and each URL also carries a {@link System#nanoTime()} path), and {@link #cleanup()}
 * removes every key the test put into the shared cache. A test can therefore never observe another
 * test's entry.
 *
 * <p>This is a framework-coupled test — it injects a CDI bean and asserts against the Infinispan
 * {@link Cache} API. See docs/framework-coupled-tests.md.
 */
class HtmlMailCachedUrlITTest extends ITTest {

    @Autowired
    MailAttachmentCache cache;

    /** Keys created during a test, removed afterwards so nothing leaks into the shared cache. */
    private final List<URL> createdKeys = new ArrayList<>();

    /** Tracks a key so {@link #cleanup()} removes it from the shared cache after the test. */
    private URL track(URL url) {
        createdKeys.add(url);
        return url;
    }

    /** Best-effort removal of every key this test created, so the shared cache stays clean. */
    @AfterEach
    void cleanup() {
        for (URL url : createdKeys) {
            try {
                cache.getCache().remove(url);
            } catch (Exception ignore) {
                // best-effort; the cache is shared and the entry may already be evicted/removed
            }
        }
        createdKeys.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // CachedUrlDataSource — getInputStream returns the bytes served by the stubbed URL
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getInputStream_returnsTheBytesServedByTheStubbedUrl() throws Exception {
        byte[] served = "PNG-bytes-from-stub".getBytes(StandardCharsets.UTF_8);

        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("image/png", served);

            URL url = track(new URL(stub.baseUrl() + "/img/" + System.nanoTime() + ".png"));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            byte[] read;
            try (InputStream in = ds.getInputStream()) {
                read = in.readAllBytes();
            }

            assertArrayEquals(served, read,
                    "getInputStream() must return exactly the bytes the stubbed URL served");
            assertEquals(1, stub.requestCount(),
                    "the source must fetch the URL exactly once");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // CachedUrlDataSource — getContentType reflects the stubbed Content-Type header
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getContentType_reflectsTheStubbedContentTypeHeader() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("image/gif", new byte[]{1, 2, 3});

            URL url = track(new URL(stub.baseUrl() + "/img/" + System.nanoTime() + ".gif"));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            assertEquals("image/gif", ds.getContentType(),
                    "getContentType() must reflect the Content-Type header sent by the stub");
        }
    }

    @Test
    void getName_isDerivedFromTheUrlPath() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("image/png", new byte[]{9});

            String fileName = "photo-" + System.nanoTime() + ".png";
            URL url = track(new URL(stub.baseUrl() + "/assets/" + fileName));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            assertEquals(fileName, ds.getName(),
                    "getName() must be the last path segment of the URL");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // CachedUrlDataSource — caching: a 2nd read does not re-hit the stub, and the entry is cached
    // ------------------------------------------------------------------------------------------- //

    @Test
    void secondGetInputStream_doesNotReHitTheStub() throws Exception {
        byte[] served = "cache-me-once".getBytes(StandardCharsets.UTF_8);

        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("application/octet-stream", served);

            URL url = track(new URL(stub.baseUrl() + "/once/" + System.nanoTime()));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            byte[] first;
            try (InputStream in = ds.getInputStream()) {
                first = in.readAllBytes();
            }
            byte[] second;
            try (InputStream in = ds.getInputStream()) {
                second = in.readAllBytes();
            }

            assertArrayEquals(served, first, "first read must return the served bytes");
            assertArrayEquals(served, second, "second read must return the same served bytes");
            assertEquals(1, stub.requestCount(),
                    "the URL must be fetched once; the second read must be served from the in-memory cache");
        }
    }

    @Test
    void loadedDataIsStoredInTheSharedCache() throws Exception {
        byte[] served = "stored-in-cache".getBytes(StandardCharsets.UTF_8);

        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("text/plain", served);

            URL url = track(new URL(stub.baseUrl() + "/cache/" + System.nanoTime()));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            assertFalse(cache.getCache().containsKey(url),
                    "precondition: nothing must be cached under this fresh URL yet");

            try (InputStream in = ds.getInputStream()) {
                in.readAllBytes();
            }

            assertTrue(cache.getCache().containsKey(url),
                    "after the first fetch the URL data must be stored in the shared cache");
            CachedUrlData cached = cache.getCache().get(url);
            assertNotNull(cached, "the cached entry must be retrievable");
            assertArrayEquals(served, cached.getContent(),
                    "the cached content must be the bytes served by the stub");
            assertEquals("text/plain", cached.getContentType(),
                    "the cached content type must be the one served by the stub");
        }
    }

    @Test
    void aPreCachedEntryIsServedWithoutHittingTheStubAtAll() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            // The stub would fail the test if hit, because we pre-seed the cache and expect zero requests.
            stub.respondBytes("text/plain", "should-not-be-served".getBytes(StandardCharsets.UTF_8));

            URL url = track(new URL(stub.baseUrl() + "/pre/" + System.nanoTime()));

            CachedUrlData seeded = new CachedUrlData();
            seeded.setUrl(url);
            seeded.setName("seeded");
            seeded.setContentType("application/pdf");
            seeded.setContent("pre-cached-bytes".getBytes(StandardCharsets.UTF_8));
            cache.getCache().put(url, seeded);

            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            byte[] read;
            try (InputStream in = ds.getInputStream()) {
                read = in.readAllBytes();
            }

            assertArrayEquals("pre-cached-bytes".getBytes(StandardCharsets.UTF_8), read,
                    "a pre-cached entry must be returned verbatim");
            assertEquals("application/pdf", ds.getContentType(),
                    "a pre-cached entry's content type must be returned verbatim");
            assertEquals(0, stub.requestCount(),
                    "a pre-cached entry must be served entirely from the cache, never hitting the network");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // CachedUrlDataSource — failure tolerance (the source must not blow up if the URL fails)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getContentType_defaultsWhenTheUrlFails() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            // 500 with no Content-Type header => the connection's getInputStream throws; the source
            // swallows it and leaves content type blank, so the documented default applies.
            stub.respondStatus(500);

            URL url = track(new URL(stub.baseUrl() + "/boom/" + System.nanoTime()));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            assertEquals("application/octet-stream", ds.getContentType(),
                    "a failed fetch must fall back to the default content type, not throw");
        }
    }

    @Test
    void getInputStream_returnsEmptyStreamWhenTheUrlFails() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            // A failed fetch leaves content null; getInputStream()
            // must serve an empty stream rather than NPE in the ByteArrayInputStream constructor at
            // mail-send time (which would defeat loadData()'s "don't fail the mail" catch).
            stub.respondStatus(500);

            URL url = track(new URL(stub.baseUrl() + "/boom/" + System.nanoTime()));
            CachedUrlDataSource ds = new CachedUrlDataSource(url, cache.getCache());

            try (InputStream in = ds.getInputStream()) {
                assertEquals(0, in.readAllBytes().length,
                        "a failed fetch must yield an empty stream, not throw NullPointerException");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // HtmlMail.fromHtml — rewrites <img src> into an inline cid: part backed by the URL
    // ------------------------------------------------------------------------------------------- //

    @Test
    void fromHtml_rewritesImgSrcIntoAnInlineCidPart() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            byte[] png = "the-image-bytes".getBytes(StandardCharsets.UTF_8);
            stub.respondBytes("image/png", png);

            String imgUrl = stub.baseUrl() + "/pic/" + System.nanoTime() + ".png";
            String html = "<html><body><p>Hello</p>"
                    + "<img src=\"" + imgUrl + "\"/></body></html>";

            HtmlMail mail = HtmlMail.fromHtml(html, stub.baseUrl(),
                    HtmlMail.StyleHandling.INLINE_CSS, false);

            // Exactly one inline part, pointing at the original image URL with a generated content id.
            List<InlineMailPart> parts = mail.getInlineParts();
            assertEquals(1, parts.size(), "the single <img src> must produce exactly one inline part");
            InlineMailPart part = parts.get(0);
            assertEquals(imgUrl, part.getUrl(),
                    "the inline part must reference the absolute image URL");
            String contentId = part.getContentId();
            assertNotNull(contentId, "the inline part must carry a generated content id");
            assertTrue(contentId.startsWith("img"),
                    "image inline parts get an 'img' cid prefix, but was: " + contentId);

            // The rewritten HTML must reference that part via a matching cid:
            String resultHtml = mail.getHtmlText();
            assertTrue(resultHtml.contains("src=\"cid:" + contentId + "\""),
                    "the <img> src must be rewritten to cid:<contentId>, but HTML was:\n" + resultHtml);
            assertFalse(resultHtml.contains("src=\"" + imgUrl + "\""),
                    "the original absolute image URL must no longer appear as the img src");

            // fromHtml only parses/rewrites the HTML — it does NOT fetch the image. The fetch happens
            // later when the mail is composed; assert the parse step performed no outbound request.
            assertEquals(0, stub.requestCount(),
                    "fromHtml must not fetch image URLs while parsing the document");
        }
    }

    @Test
    void fromHtml_dedupesRepeatedImageUrlsIntoASingleInlinePart() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("image/png", new byte[]{1});

            String imgUrl = stub.baseUrl() + "/dup/" + System.nanoTime() + ".png";
            String html = "<html><body>"
                    + "<img src=\"" + imgUrl + "\"/>"
                    + "<img src=\"" + imgUrl + "\"/>"
                    + "</body></html>";

            HtmlMail mail = HtmlMail.fromHtml(html, stub.baseUrl(),
                    HtmlMail.StyleHandling.INLINE_CSS, false);

            assertEquals(1, mail.getInlineParts().size(),
                    "two <img> tags for the SAME url must share a single inline part");
            String contentId = mail.getInlineParts().get(0).getContentId();

            // Both <img> tags must be rewritten to the same cid.
            int occurrences = countOccurrences(mail.getHtmlText(), "src=\"cid:" + contentId + "\"");
            assertEquals(2, occurrences,
                    "both <img> tags must reference the shared cid, but HTML was:\n" + mail.getHtmlText());
        }
    }

    @Test
    void fromHtml_inlinePart_resolvesItsContentThroughTheCachedDataHandler() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            byte[] png = "resolved-via-datahandler".getBytes(StandardCharsets.UTF_8);
            stub.respondBytes("image/png", png);

            String imgUrl = stub.baseUrl() + "/resolve/" + System.nanoTime() + ".png";
            track(new URL(imgUrl));
            String html = "<html><body><img src=\"" + imgUrl + "\"/></body></html>";

            HtmlMail mail = HtmlMail.fromHtml(html, stub.baseUrl(),
                    HtmlMail.StyleHandling.INLINE_CSS, false);

            InlineMailPart part = mail.getInlineParts().get(0);

            // Driving the inline part's data handler through the cache is what actually fetches the URL.
            Object content;
            try (InputStream in = part.getDataHandler(cache.getCache()).getInputStream()) {
                content = in.readAllBytes();
            }

            assertArrayEquals(png, (byte[]) content,
                    "the inline part's data handler must yield the bytes served by the stub");
            assertEquals(1, stub.requestCount(),
                    "resolving the inline part must fetch the image exactly once");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // HtmlMail.fromHtml — robustness / edge cases
    // ------------------------------------------------------------------------------------------- //

    @Test
    void fromHtml_toleratesMalformedHtmlWithoutThrowing() {
        // Unclosed tags, stray angle brackets, missing closing markup: Jsoup is forgiving, and
        // fromHtml must not propagate a parse exception.
        String malformed = "<html><body><p>oops <img src= <div><span>unbalanced</body";

        HtmlMail mail = assertDoesNotThrow(
                () -> HtmlMail.fromHtml(malformed, "http://example.test",
                        HtmlMail.StyleHandling.INLINE_CSS, false),
                "fromHtml must tolerate malformed HTML rather than throwing");

        assertNotNull(mail.getHtmlText(),
                "even for malformed input fromHtml must produce some HTML output");
    }

    @Test
    void fromHtml_blankImgSrcIsDroppedAndCreatesNoInlinePart() throws Exception {
        // Jsoup's absUrl("src") resolves a blank src=""
        // against the document base URI (never empty), so the url.length()==0 "remove" branch never
        // fires for a blank src. inlineImages() now guards the raw attribute first, so an <img src="">
        // is dropped instead of becoming a spurious base-URI inline part.
        String html = "<html><body><img src=\"\"/><p>kept</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, "http://example.test",
                HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty(),
                "a blank src must be dropped, yielding no inline part");
        assertFalse(mail.getHtmlText().contains("cid:"),
                "no cid: reference must be emitted for a dropped blank <img>");
        assertTrue(mail.getHtmlText().contains("kept"),
                "the rest of the document must be preserved");
    }

    @Test
    void fromHtml_withNoImagesProducesNoInlineParts() throws Exception {
        String html = "<html><body><h1>No images here</h1><p>Just text.</p></body></html>";

        HtmlMail mail = HtmlMail.fromHtml(html, "http://example.test",
                HtmlMail.StyleHandling.INLINE_CSS, false);

        assertTrue(mail.getInlineParts().isEmpty(),
                "a document with no images must produce no inline parts");
        assertFalse(mail.getHtmlText().contains("cid:"),
                "no cid: references must appear when there are no inlined resources");
    }

    /** Counts non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
