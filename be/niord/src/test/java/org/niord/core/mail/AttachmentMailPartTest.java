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

import org.junit.jupiter.api.Test;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the {@link AttachmentMailPart} factory methods.
 *
 * <p>These tests pin the observable contract of the static factories:
 * <ul>
 *     <li>{@link AttachmentMailPart#fromContent(byte[], String)} and the three-arg overload,</li>
 *     <li>{@link AttachmentMailPart#fromUrl(String)} and the two-arg overload,</li>
 *     <li>{@link AttachmentMailPart#fromFile(String)} and the two-arg overload.</li>
 * </ul>
 *
 * <p>For {@code fromUrl} and {@code fromFile} the first argument is the URL / file path and the
 * optional second argument is the attachment name. When the name is blank or omitted it is derived
 * from the URL path (last segment) or the file name. The value that is validated (parsed as a URL
 * or checked for being a regular file) is always the first argument.
 */
public class AttachmentMailPartTest {

    // ------------------------------------------------------------------
    // fromContent(byte[], name)  -> delegates to fromContent(byte[], null, name)
    // ------------------------------------------------------------------

    @Test
    public void fromContentTwoArgUsesNameAndDefaultContentType() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();
        String name = "report-" + System.nanoTime() + ".bin";

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, name);

        assertEquals(name, part.getName());

        // fromContent(bytes, name) delegates to fromContent(bytes, null, name), so the content type
        // defaults to "application/octet-stream".
        DataHandler dh = part.getDataHandler(null);
        assertNotNull(dh);
        assertEquals("application/octet-stream", dh.getContentType());
    }

    // ------------------------------------------------------------------
    // fromContent(byte[], contentType, name)
    // ------------------------------------------------------------------

    @Test
    public void fromContentThreeArgKeepsExplicitContentType() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();
        String name = "page-" + System.nanoTime() + ".html";

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, "text/html", name);

        assertEquals(name, part.getName());
        assertEquals("text/html", part.getDataHandler(null).getContentType());
    }

    @Test
    public void fromContentThreeArgBlankContentTypeFallsBackToDefault() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();
        String name = "blank-ct-" + System.nanoTime() + ".bin";

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, "   ", name);

        assertEquals(name, part.getName());
        assertEquals("application/octet-stream", part.getDataHandler(null).getContentType());
    }

    @Test
    public void fromContentThreeArgEmptyContentTypeFallsBackToDefault() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();
        String name = "empty-ct-" + System.nanoTime() + ".bin";

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, "", name);

        assertEquals("application/octet-stream", part.getDataHandler(null).getContentType());
    }

    @Test
    public void fromContentThreeArgNullContentTypeFallsBackToDefault() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();
        String name = "null-ct-" + System.nanoTime() + ".bin";

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, null, name);

        assertEquals("application/octet-stream", part.getDataHandler(null).getContentType());
    }

    @Test
    public void fromContentBlankNameFallsBackToUnknown() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, "text/plain", "   ");

        assertEquals("unknown", part.getName());
    }

    @Test
    public void fromContentNullNameFallsBackToUnknown() throws MessagingException {
        byte[] bytes = ("payload-" + System.nanoTime()).getBytes();

        AttachmentMailPart part = AttachmentMailPart.fromContent(bytes, "text/plain", null);

        assertEquals("unknown", part.getName());
    }

    @Test
    public void fromContentNullBytesThrowsMessagingException() {
        try {
            AttachmentMailPart.fromContent(null, "name.bin");
            fail("Expected MessagingException for null content byte array");
        } catch (MessagingException e) {
            assertEquals("Invalid empty content byte array", e.getMessage());
        }
    }

    @Test
    public void fromContentEmptyBytesThrowsMessagingException() {
        try {
            AttachmentMailPart.fromContent(new byte[0], "name.bin");
            fail("Expected MessagingException for empty content byte array");
        } catch (MessagingException e) {
            assertEquals("Invalid empty content byte array", e.getMessage());
        }
    }

    @Test
    public void fromContentThreeArgNullBytesThrowsMessagingException() {
        try {
            AttachmentMailPart.fromContent(null, "text/plain", "name.bin");
            fail("Expected MessagingException for null content byte array");
        } catch (MessagingException e) {
            assertEquals("Invalid empty content byte array", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // fromUrl(url) / fromUrl(url, name)
    // ------------------------------------------------------------------

    @Test
    public void fromUrlSingleArgDerivesNameFromUrlPath() throws MessagingException {
        // fromUrl(url) -> fromUrl(url, null): with no explicit name, the name defaults to the
        // last segment of the URL path.
        AttachmentMailPart part = AttachmentMailPart.fromUrl("http://example.com/path/file.pdf");

        assertEquals("file.pdf", part.getName());
        assertNotNull(part.getDataHandler(null));
    }

    @Test
    public void fromUrlTwoArgKeepsExplicitName() throws MessagingException {
        // fromUrl(url, name): the URL (first argument) is validated and the explicit name is kept.
        AttachmentMailPart part =
                AttachmentMailPart.fromUrl("http://example.com/path/file.pdf", "custom-name.dat");

        assertEquals("custom-name.dat", part.getName());
        assertNotNull(part.getDataHandler(null));
    }

    @Test
    public void fromUrlBlankNameFallsBackToUrlPath() throws MessagingException {
        // A blank name is treated as "not specified" and is derived from the URL path.
        AttachmentMailPart part =
                AttachmentMailPart.fromUrl("http://example.com/path/report.csv", "   ");

        assertEquals("report.csv", part.getName());
    }

    @Test
    public void fromUrlMalformedUrlThrowsMessagingException() {
        // The first argument is the URL and is the value that is parsed/validated.
        try {
            AttachmentMailPart.fromUrl("not a url", "display-name");
            fail("Expected MessagingException for the malformed URL argument");
        } catch (MessagingException e) {
            assertEquals("Invalid url attachment: not a url", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // fromFile(path) / fromFile(path, name)
    // ------------------------------------------------------------------

    @Test
    public void fromFileSingleArgDerivesNameFromFileName() throws Exception {
        // fromFile(path) -> fromFile(path, null): the existing file is accepted and the name
        // defaults to the file's own name.
        File temp = File.createTempFile("att-mail-part-", ".tmp");
        try {
            AttachmentMailPart part = AttachmentMailPart.fromFile(temp.getAbsolutePath());

            assertEquals(temp.getName(), part.getName());
            assertNotNull(part.getDataHandler(null));
        } finally {
            temp.delete();
        }
    }

    @Test
    public void fromFileTwoArgKeepsExplicitName() throws Exception {
        // fromFile(realPath, "att.txt"): the existing file (first argument) is accepted and the
        // explicit name is kept.
        File temp = File.createTempFile("att-mail-part-", ".tmp");
        try {
            AttachmentMailPart part = AttachmentMailPart.fromFile(temp.getAbsolutePath(), "att.txt");

            assertEquals("att.txt", part.getName());
            assertNotNull(part.getDataHandler(null));
        } finally {
            temp.delete();
        }
    }

    @Test
    public void fromFileNonexistentPathThrowsMessagingException() {
        // The first argument is the file path and is the value that is checked for existence.
        try {
            AttachmentMailPart.fromFile("att.txt", "display-name");
            fail("Expected MessagingException for a path that is not a regular file");
        } catch (MessagingException e) {
            assertEquals("Invalid file attachment: att.txt", e.getMessage());
        }
    }
}
