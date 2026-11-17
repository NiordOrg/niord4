/*
 * Copyright 2026 Danish Maritime Authority.
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

import org.junit.jupiter.api.Test;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier-1 tests for what {@link CachedUrlDataSource} agrees to read, without the shared cache (the cache
 * wiring is covered by {@code HtmlMailCachedUrlITTest}). It reads http(s) URLs only, and follows a
 * redirect only within the origin of the URL it was given: {@link HtmlMail} decides which origin a mail
 * fetches from, and a redirect must not take the fetch elsewhere.
 */
class CachedUrlDataSourceTest {

    private static byte[] read(CachedUrlDataSource ds) throws Exception {
        try (InputStream in = ds.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void aFileUrlIsNotRead() throws Exception {
        Path file = Files.createTempFile("mail-part", ".txt");
        try {
            Files.writeString(file, "local file content");

            CachedUrlDataSource ds = new CachedUrlDataSource(file.toUri().toURL(), null);

            assertEquals(0, read(ds).length, "a file: URL must not be read into a mail");
            assertEquals("application/octet-stream", ds.getContentType());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aJarUrlIsNotRead() throws Exception {
        Path archive = Files.createTempFile("mail-part", ".jar");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("application.yml"));
                zip.write("archived content".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            URL jar = URI.create("jar:" + archive.toUri() + "!/application.yml").toURL();

            assertEquals(0, read(new CachedUrlDataSource(jar, null)).length, "a jar: URL must not be read into a mail");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void anHttpUrlIsRead() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondBytes("image/png", "png".getBytes(StandardCharsets.UTF_8));

            CachedUrlDataSource ds = new CachedUrlDataSource(URI.create(stub.baseUrl() + "/img/a.png").toURL(), null);

            assertArrayEquals("png".getBytes(StandardCharsets.UTF_8), read(ds));
            assertEquals("image/png", ds.getContentType());
        }
    }

    @Test
    void aRedirectWithinTheOriginIsFollowed() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respond(req -> req.path().startsWith("/rest/")
                    ? StubResponse.redirect(302, "/img/placeholder.png")
                    : StubResponse.bytes("image/png", new byte[] { 1, 2 }));

            CachedUrlDataSource ds = new CachedUrlDataSource(
                    URI.create(stub.baseUrl() + "/rest/message-map-image/1.png").toURL(), null);

            assertArrayEquals(new byte[] { 1, 2 }, read(ds));
            assertEquals("/img/placeholder.png", stub.lastRequest().path());
        }
    }

    @Test
    void aRedirectToAnotherOriginIsNotFollowed() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondWith(StubResponse.redirect(302, other.baseUrl() + "/internal"));
            other.respondBytes("text/plain", "internal".getBytes(StandardCharsets.UTF_8));

            CachedUrlDataSource ds = new CachedUrlDataSource(URI.create(own.baseUrl() + "/img/a.png").toURL(), null);

            assertEquals(0, read(ds).length);
            assertEquals(0, other.requestCount(), "the other origin must never be asked");
        }
    }
}
