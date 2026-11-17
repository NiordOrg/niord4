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
package org.niord.core.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link MessageLinks#rewriteHashLinks(String, String, String)}, which turns the hash-routed
 * message links {@code <baseUri>/#/message/<id>} that stored templates and tweet formats may build into
 * {@code <linkBaseUri>/messages?messageId=<id>}.
 *
 * <p>The id is the part that can go wrong: it ends at the first quote, tag start or whitespace, arrives
 * percent-encoded or not, and must come out encoded exactly once as a query value. The expected values are
 * what the administration web app's {@code decodeURIComponent} reads back as the id.
 */
public class MessageLinksTest {

    private static final String BASE_URI = "http://localhost:8888";
    private static final String ADMIN_APP = "https://admin.example.org";

    private static String rewrite(String text) {
        return MessageLinks.rewriteHashLinks(text, BASE_URI, ADMIN_APP);
    }

    @Test
    public void rewritesTheLinkInAnAnchor() {
        assertEquals(
                "<a href=\"https://admin.example.org/messages?messageId=NW-015-17\" target=\"_blank\">NW-015-17</a>",
                rewrite("<a href=\"http://localhost:8888/#/message/NW-015-17\" target=\"_blank\">NW-015-17</a>"));
    }

    @Test
    public void rewritesEveryLinkInTheText() {
        assertEquals(
                "<a href='https://admin.example.org/messages?messageId=a'>a</a>"
                        + " https://admin.example.org/messages?messageId=b\n"
                        + "https://admin.example.org/messages?messageId=c<br>",
                rewrite("<a href='http://localhost:8888/#/message/a'>a</a>"
                        + " http://localhost:8888/#/message/b\n"
                        + "http://localhost:8888/#/message/c<br>"));
    }

    @Test
    public void theIdEndsAtTheEndOfTheText() {
        assertEquals("NW-015-17 Buoy missing https://admin.example.org/messages?messageId=5a8c2f9e-1d2b",
                rewrite("NW-015-17 Buoy missing http://localhost:8888/#/message/5a8c2f9e-1d2b"));
    }

    @Test
    public void encodesAnIdWithSpacesAndReservedCharactersOnce() {
        // Built with the id as written, and with the id percent-encoded: both mean the id "B/1 2024&x=é"
        String expected = "https://admin.example.org/messages?messageId=B%2F1%202024%26x%3D%C3%A9";
        assertEquals(expected, rewrite("http://localhost:8888/#/message/B/1%202024&x=é"));
        assertEquals(expected, rewrite("http://localhost:8888/#/message/B%2F1%202024%26x%3D%C3%A9"));
    }

    @Test
    public void anEncodedQuoteInTheIdStaysEncoded() {
        // A raw quote would end a single-quoted href attribute
        assertEquals("<a href='https://admin.example.org/messages?messageId=A%27B%281%29%21%7E'>x</a>",
                rewrite("<a href='http://localhost:8888/#/message/A%27B(1)!~'>x</a>"));
    }

    @Test
    public void aPlusInTheIdIsAPlusSign() {
        assertEquals("https://admin.example.org/messages?messageId=NM%2B1",
                rewrite("http://localhost:8888/#/message/NM+1"));
    }

    @Test
    public void anIdWithAMalformedEscapeIsTakenAsWritten() {
        assertEquals("https://admin.example.org/messages?messageId=50%25off",
                rewrite("http://localhost:8888/#/message/50%off"));
    }

    @Test
    public void aBaseUriWithATrailingSlashMatchesTheLinksItBuilt() {
        String expected = "go to https://admin.example.org/messages?messageId=x";
        assertEquals(expected,
                MessageLinks.rewriteHashLinks("go to http://localhost:8888//#/message/x", BASE_URI + "/", ADMIN_APP));
        assertEquals(expected,
                MessageLinks.rewriteHashLinks("go to http://localhost:8888/#/message/x", BASE_URI + "/", ADMIN_APP));
    }

    @Test
    public void aLinkBaseUriWithATrailingSlashGivesASingleSlash() {
        assertEquals("http://localhost:8888/messages?messageId=x",
                MessageLinks.rewriteHashLinks("http://localhost:8888/#/message/x", BASE_URI, BASE_URI + "/"));
    }

    @Test
    public void textWithoutHashLinksIsUnchanged() {
        String text = "<p>See <a href=\"http://localhost:8888/rest/repo/file/x.pdf\">x</a>,"
                + " https://admin.example.org/messages?messageId=NW-1, http://other.example.org/#/message/y"
                + " and http://localhost:8888/#/message/ with no id.</p>";
        assertEquals(text, rewrite(text));
    }

    @Test
    public void emptyTextOrBaseUriLeavesTheTextAlone() {
        assertNull(rewrite(null));
        assertEquals("", rewrite(""));
        assertEquals("/#/message/x", MessageLinks.rewriteHashLinks("/#/message/x", "", ADMIN_APP));
        assertEquals("/#/message/x", MessageLinks.rewriteHashLinks("/#/message/x", null, ADMIN_APP));
    }
}
