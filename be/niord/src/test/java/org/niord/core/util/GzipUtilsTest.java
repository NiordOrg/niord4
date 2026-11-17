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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link GzipUtils}, covering GZIP compression/decompression round-trips,
 * the null-handling contract, the gzip magic-byte header, the *IgnoreError variants that
 * swallow IOExceptions, and the lossless preservation of newlines through a decompress round-trip.
 */
public class GzipUtilsTest {

    // ------------------------------------------------------------------
    // compressString / decompressString round-trips
    // ------------------------------------------------------------------

    @Test
    public void roundTrip_singleLineUnicode_preservesContent() throws IOException {
        String original = "Hello, world! æøå";
        byte[] compressed = GzipUtils.compressString(original);
        assertEquals(original, GzipUtils.decompressString(compressed));
    }

    @Test
    public void roundTrip_emptyString_returnsEmptyString() throws IOException {
        byte[] compressed = GzipUtils.compressString("");
        // Empty input yields zero chars read, so an empty string round-trips.
        assertEquals("", GzipUtils.decompressString(compressed));
    }

    @Test
    public void roundTrip_asciiOnly_preservesContent() throws IOException {
        String original = "The quick brown fox jumps over the lazy dog 1234567890";
        assertEquals(original, GzipUtils.decompressString(GzipUtils.compressString(original)));
    }

    @Test
    public void roundTrip_longString_preservesContent() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("abcdefghij");
        }
        String original = sb.toString();
        assertEquals(original, GzipUtils.decompressString(GzipUtils.compressString(original)));
    }

    @Test
    public void roundTrip_unicodeWithSpacesButNoNewlines_preservesContent() throws IOException {
        String original = "位置 N 56° 10' E 等等 — punctuation, tabs:\tend";
        assertEquals(original, GzipUtils.decompressString(GzipUtils.compressString(original)));
    }

    // ------------------------------------------------------------------
    // gzip header / output format
    // ------------------------------------------------------------------

    @Test
    public void compressString_outputStartsWithGzipMagicBytes() throws IOException {
        byte[] compressed = GzipUtils.compressString("anything");
        assertNotNull(compressed);
        assertTrue(compressed.length >= 2, "compressed output too short to contain gzip header");
        assertEquals(0x1f, compressed[0] & 0xff);
        assertEquals(0x8b, compressed[1] & 0xff);
    }

    @Test
    public void compressString_emptyInput_stillProducesGzipHeader() throws IOException {
        byte[] compressed = GzipUtils.compressString("");
        assertNotNull(compressed);
        assertTrue(compressed.length >= 2);
        assertEquals(0x1f, compressed[0] & 0xff);
        assertEquals(0x8b, compressed[1] & 0xff);
    }

    // ------------------------------------------------------------------
    // null handling
    // ------------------------------------------------------------------

    @Test
    public void compressString_null_returnsNull() throws IOException {
        assertNull(GzipUtils.compressString(null));
    }

    @Test
    public void decompressString_null_returnsNull() throws IOException {
        assertNull(GzipUtils.decompressString(null));
    }

    @Test
    public void compressStringIgnoreError_null_returnsNull() {
        assertNull(GzipUtils.compressStringIgnoreError(null));
    }

    @Test
    public void decompressStringIgnoreError_null_returnsNull() {
        assertNull(GzipUtils.decompressStringIgnoreError(null));
    }

    // ------------------------------------------------------------------
    // *IgnoreError variants: success and swallowed-error behavior
    // ------------------------------------------------------------------

    @Test
    public void compressStringIgnoreError_validInput_matchesCompressString() throws IOException {
        String original = "round-trip via ignore-error variant";
        byte[] viaIgnore = GzipUtils.compressStringIgnoreError(original);
        assertNotNull(viaIgnore);
        // The ignore-error variant must produce a decompressible payload identical to the input.
        assertEquals(original, GzipUtils.decompressString(viaIgnore));
    }

    @Test
    public void decompressStringIgnoreError_validInput_roundTrips() {
        String original = "ignore-error decompression path";
        byte[] compressed = GzipUtils.compressStringIgnoreError(original);
        assertEquals(original, GzipUtils.decompressStringIgnoreError(compressed));
    }

    @Test
    public void decompressStringIgnoreError_invalidGzip_returnsNull() {
        // Not a valid gzip stream (missing magic bytes) -> GZIPInputStream throws IOException,
        // which the *IgnoreError variant swallows and returns null.
        assertNull(GzipUtils.decompressStringIgnoreError(new byte[]{1, 2, 3}));
    }

    @Test
    public void decompressStringIgnoreError_emptyByteArray_returnsNull() {
        // An empty (but non-null) array is not a valid gzip stream.
        assertNull(GzipUtils.decompressStringIgnoreError(new byte[0]));
    }

    @Test
    public void decompressString_invalidGzip_throwsIOException() {
        try {
            GzipUtils.decompressString(new byte[]{1, 2, 3});
            fail("Expected IOException for non-gzip input");
        } catch (IOException expected) {
            // Expected: GZIPInputStream rejects data that does not start with the gzip header.
        }
    }

    // ------------------------------------------------------------------
    // Multi-line content survives the round-trip losslessly (docs/potential-errors.md U3 fixed)
    // ------------------------------------------------------------------

    @Test
    public void decompress_preservesNewlines() throws IOException {
        // Fixed (U3): decompressString now copies the reader char-by-char, so internal newlines
        // survive and the round-trip is lossless ("a\nb\nc" -> "a\nb\nc"). It previously read with
        // BufferedReader.readLine() and dropped every separator, yielding "abc".
        byte[] compressed = GzipUtils.compressString("a\nb\nc");
        assertEquals("a\nb\nc", GzipUtils.decompressString(compressed));
    }

    @Test
    public void decompress_preservesTrailingNewline() throws IOException {
        // A trailing newline is also preserved: "line1\n" round-trips unchanged.
        byte[] compressed = GzipUtils.compressString("line1\n");
        assertEquals("line1\n", GzipUtils.decompressString(compressed));
    }

    @Test
    public void decompress_preservesCrlf() throws IOException {
        // CRLF separators are preserved verbatim rather than collapsed.
        byte[] compressed = GzipUtils.compressString("first\r\nsecond");
        assertEquals("first\r\nsecond", GzipUtils.decompressString(compressed));
    }

    @Test
    public void roundTrip_multiLineUnicode_preservesEveryCharacter() throws IOException {
        // A realistic multi-line payload (the kind stored as gzipped mail/message HTML) must
        // round-trip byte-for-byte, including blank lines, CRLFs and non-ASCII characters.
        String original = "<p>Linje 1 — æøå</p>\n\n<p>Line 2</p>\r\nTrailing\n";
        assertEquals(original, GzipUtils.decompressString(GzipUtils.compressString(original)));
    }
}
