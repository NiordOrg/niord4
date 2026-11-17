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
package org.niord.core.repo;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RepositoryService#fileSecurityHeaders}: the headers a repository file is streamed
 * with, so that an uploaded file never runs as active content on the backend origin.
 *
 * <p>Every file carries {@code nosniff}; every file but a PDF carries {@code Content-Security-Policy: sandbox}
 * (the public site's nginx policy for the same files); and only the passive types the applications show
 * inline (raster images, PDF, audio, video) are sent with an inline disposition, everything else as an
 * attachment. Both dispositions carry the file's name.
 */
class RepositoryFileSecurityHeadersTest {

    @Test
    void activeAndUnknownTypesAreSandboxedAttachments() {
        for (MediaType type : List.of(MediaType.TEXT_HTML, MediaType.parseMediaType("image/svg+xml"),
                MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_OCTET_STREAM,
                MediaType.TEXT_PLAIN, MediaType.parseMediaType("text/richtext"))) {
            HttpHeaders headers = RepositoryService.fileSecurityHeaders(type, "page.html");

            assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"), type + " must carry nosniff");
            assertEquals("sandbox", headers.getFirst("Content-Security-Policy"), type + " must be sandboxed");
            assertTrue(headers.getContentDisposition().isAttachment(), type + " must be sent as an attachment");
        }
    }

    @Test
    void imagesMediaAndPdfAreShownInline() {
        for (MediaType type : List.of(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.IMAGE_GIF,
                MediaType.parseMediaType("image/bmp"), MediaType.parseMediaType("image/tiff"),
                MediaType.parseMediaType("video/mp4"), MediaType.parseMediaType("audio/mpeg"),
                MediaType.APPLICATION_PDF)) {
            HttpHeaders headers = RepositoryService.fileSecurityHeaders(type, "file.bin");

            assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"), type + " must carry nosniff");
            assertTrue(headers.getContentDisposition().isInline(), type + " must be shown inline");
            assertEquals("file.bin", headers.getContentDisposition().getFilename(),
                    type + " must be named, not left to a default name");
        }
    }

    @Test
    void everyFileButAPdfIsSandboxed() {
        assertEquals("sandbox", RepositoryService.fileSecurityHeaders(MediaType.IMAGE_PNG, "a.png")
                .getFirst("Content-Security-Policy"), "an image is sandboxed like any other file");
        assertEquals("sandbox", RepositoryService.fileSecurityHeaders(MediaType.parseMediaType("video/mp4"), "a.mp4")
                .getFirst("Content-Security-Policy"), "a video is sandboxed like any other file");
        assertNull(RepositoryService.fileSecurityHeaders(MediaType.APPLICATION_PDF, "a.pdf")
                .getFirst("Content-Security-Policy"), "a PDF is not sandboxed, so every PDF viewer opens it");
    }

    @Test
    void theAttachmentKeepsANonAsciiFileName() {
        HttpHeaders headers = RepositoryService.fileSecurityHeaders(MediaType.TEXT_HTML, "søkort 12.html");

        assertEquals("søkort 12.html", headers.getContentDisposition().getFilename());
        assertEquals(StandardCharsets.UTF_8, headers.getContentDisposition().getCharset());
    }
}
