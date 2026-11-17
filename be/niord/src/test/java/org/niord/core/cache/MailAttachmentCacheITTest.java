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
package org.niord.core.cache;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.infinispan.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.CachedUrlData;
import org.niord.core.mail.MailAttachmentCache;
import org.niord.test.ITTest;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link org.niord.core.cache.BaseCache} behaviour exercised through the
 * concrete {@link MailAttachmentCache} bean.
 *
 * <p>{@link MailAttachmentCache} is an {@code @ApplicationScoped} bean that lazily starts an
 * embedded, in-memory Infinispan {@code LOCAL} cache in its {@code @PostConstruct}. The cache is a
 * single shared singleton for the whole Quarkus test JVM, so it is NOT isolated between tests by a
 * database transaction or by {@code @Transactional}. To keep the tests independent and
 * order-independent, every test uses a UNIQUE URL key built from {@link System#nanoTime()} and
 * removes its own key in {@link #cleanup()} (best-effort). No two tests share a key, so a put by one
 * test can never be observed by another.
 *
 * <p>No database is required for these tests, but the standard {@code @QuarkusTest} +
 * {@link NiordContainersResource} + {@link ITTest} wiring is kept so the bean is resolved and
 * injected exactly as it is in the running application.
 *
 * <p>This is a framework-coupled test — it injects a CDI bean and asserts against the Infinispan
 * {@link Cache} API. See docs/framework-coupled-tests.md.
 */
class MailAttachmentCacheITTest extends ITTest {

    @Autowired
    MailAttachmentCache cache;

    /** Keys created during a test, removed afterwards so nothing leaks into other tests. */
    private final List<URL> createdKeys = new ArrayList<>();

    /** Builds a unique http URL that will not collide with any other test's key. */
    private URL uniqueUrl() throws Exception {
        URL url = new URL("http://it-cache-test/" + System.nanoTime());
        createdKeys.add(url);
        return url;
    }

    /** Builds a CachedUrlData carrying the supplied name/contentType and a small content payload. */
    private CachedUrlData data(URL url, String name, String contentType, byte[] content) {
        CachedUrlData d = new CachedUrlData();
        d.setUrl(url);
        d.setName(name);
        d.setContentType(contentType);
        d.setContent(content);
        return d;
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
    // getCacheId
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getCacheIdReturnsTheMailAttachmentCacheName() {
        assertEquals("mailAttachmentCache", cache.getCacheId(),
                "MailAttachmentCache must expose the documented cache id");
    }

    // ------------------------------------------------------------------------------------------- //
    // getCache — put/get round-trip and identity
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getCacheReturnsAUsableCacheNamedAfterTheCacheId() throws Exception {
        Cache<URL, CachedUrlData> c = cache.getCache();
        assertNotNull(c, "getCache() must return a live Infinispan cache");
        assertEquals("mailAttachmentCache", c.getName(),
                "the returned cache must be the one configured under getCacheId()");
    }

    @Test
    void putThenGetRoundTripsTheStoredValue() throws Exception {
        URL url = uniqueUrl();
        byte[] content = "the-bytes".getBytes();
        CachedUrlData stored = data(url, "attachment.pdf", "application/pdf", content);

        cache.getCache().put(url, stored);

        // CachedUrlData has no equals(): compare the meaningful fields explicitly.
        CachedUrlData fetched = cache.getCache().get(url);
        assertNotNull(fetched, "a value put under a key must be retrievable by that key");
        assertEquals("attachment.pdf", fetched.getName());
        assertEquals("application/pdf", fetched.getContentType());
        assertEquals(url, fetched.getUrl());
        assertArrayEquals(content, fetched.getContent(),
                "the cached byte content must round-trip unchanged");
    }

    @Test
    void containsKeyIsTrueAfterPut() throws Exception {
        URL url = uniqueUrl();
        cache.getCache().put(url, data(url, "a.txt", "text/plain", new byte[0]));

        assertTrue(cache.getCache().containsKey(url),
                "containsKey must report true for a key that was just put");
    }

    @Test
    void putReplacesTheValueForAnExistingKey() throws Exception {
        URL url = uniqueUrl();
        cache.getCache().put(url, data(url, "first", "text/plain", new byte[0]));
        cache.getCache().put(url, data(url, "second", "text/html", new byte[0]));

        CachedUrlData fetched = cache.getCache().get(url);
        assertEquals("second", fetched.getName(),
                "a second put under the same key must overwrite the first value");
        assertEquals("text/html", fetched.getContentType());
    }

    @Test
    void getCacheReturnsTheSameSharedCacheInstanceOnRepeatedCalls() {
        // BaseCache.getCache() looks the entry cache up by name each call; the manager returns the
        // same live cache instance, which is why entries put via one call are visible via another.
        Cache<URL, CachedUrlData> first = cache.getCache();
        Cache<URL, CachedUrlData> second = cache.getCache();
        assertSame(first, second,
                "repeated getCache() calls must yield the same shared cache instance");
    }

    // ------------------------------------------------------------------------------------------- //
    // get / containsKey for absent keys
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getReturnsNullForAnAbsentKey() throws Exception {
        URL url = uniqueUrl();
        // Never put under this key.
        assertNull(cache.getCache().get(url),
                "get() of a key that was never stored must return null");
    }

    @Test
    void containsKeyIsFalseForAnAbsentKey() throws Exception {
        URL url = uniqueUrl();
        assertFalse(cache.getCache().containsKey(url),
                "containsKey of a key that was never stored must be false");
    }

    @Test
    void removeMakesAKeyAbsentAgain() throws Exception {
        URL url = uniqueUrl();
        cache.getCache().put(url, data(url, "tmp", "text/plain", new byte[0]));
        assertTrue(cache.getCache().containsKey(url), "precondition: the key must be present");

        cache.getCache().remove(url);

        assertFalse(cache.getCache().containsKey(url),
                "after remove() the key must no longer be present");
        assertNull(cache.getCache().get(url),
                "after remove() get() of the key must return null");
    }

    // ------------------------------------------------------------------------------------------- //
    // clearCache
    // ------------------------------------------------------------------------------------------- //

    @Test
    void clearCacheRemovesAPreviouslyStoredEntry() throws Exception {
        URL url = uniqueUrl();
        cache.getCache().put(url, data(url, "to-be-cleared", "text/plain", new byte[]{1, 2, 3}));
        assertTrue(cache.getCache().containsKey(url),
                "precondition: the entry must be present before clearCache()");

        cache.clearCache();

        // The cache is shared, so we assert against OUR unique key rather than the whole-cache size:
        // clearCache() empties the cache, so our key must be gone afterwards.
        assertFalse(cache.getCache().containsKey(url),
                "clearCache() must remove every entry, including our unique key");
        assertNull(cache.getCache().get(url),
                "clearCache() must make a previously stored entry unretrievable");
    }
}
