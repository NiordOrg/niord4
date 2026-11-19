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
import org.niord.core.util.WebUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RepoPaths#fileLinkPaths} and {@link RepoPaths#candidates}: the one rule that reads a served file's link
 * ({@code /rest/repo/file/<path>}) back to the rows it may name, shared by the import's verification and by the
 * delete of an issue in a legacy publication's folder.
 */
class RepoFileLinksTest {

    private static final String FOLDER = "publications/x/uuid/1";

    @Test
    void theLinksOfATextAreFoundInOrderAndEndWhereTheUrlDoes() {
        String html = "<p>See <a href=\"/rest/repo/file/" + FOLDER + "/efs%20uge%2036.pdf?x=1\">the edition</a> and "
                + "<a href='/rest/repo/file/" + FOLDER + "/nm.pdf#page=2'>page 2</a>, or /rest/repo/file/" + FOLDER
                + "/bare.pdf in a sentence.</p>";
        assertEquals(List.of(FOLDER + "/efs%20uge%2036.pdf", FOLDER + "/nm.pdf", FOLDER + "/bare.pdf"),
                RepoPaths.fileLinkPaths(html), "cut at the quote, the query string, the fragment and the white space");
    }

    /** A legacy publication's link is the bare URL, and encodeURI keeps parentheses: they are part of the name */
    @Test
    void aParenthesisIsPartOfTheName() {
        String link = "/rest/repo/file/" + FOLDER + "/EfS%20uge%2036%20(rettet).pdf";
        assertEquals(List.of(FOLDER + "/EfS%20uge%2036%20(rettet).pdf"), RepoPaths.fileLinkPaths(link));
    }

    /**
     * REPO4: a name holding a question mark or an apostrophe is written encoded in the link Niord makes
     * (RepositoryService.getRepoUri, WebUtils.encodeURI), so the reader's cut at "?" and "'" falls after the name
     */
    @Test
    void aNameWithAQuestionMarkOrAnApostropheIsReadBackWhole() {
        String what = FOLDER + "/what?.pdf";
        String its = FOLDER + "/it's (1).pdf";
        String html = "<p><a href=\"" + RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI(what) + "\">what</a> "
                + "<img src='" + RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI(its) + "?t=1'></p>";
        List<String> found = RepoPaths.fileLinkPaths(html);
        assertEquals(List.of(FOLDER + "/what%3F.pdf", FOLDER + "/it%27s%20(1).pdf"), found,
                "cut after the name: at the closing quote and at the image's own query string");
        assertTrue(RepoPaths.candidates(found.get(0)).contains(what), "" + found);
        assertTrue(RepoPaths.candidates(found.get(1)).contains(its), "" + found);
    }

    /**
     * A link stored before REPO4's fix keeps its raw form (nothing changes a deployment's data), and is read as it
     * was: cut at the raw "?" or "'". Pinned so a change shows.
     */
    @Test
    void aLinkStoredRawIsReadAsBefore_documentsBehavior() {
        assertEquals(List.of(FOLDER + "/what", FOLDER + "/it"), RepoPaths.fileLinkPaths(
                "<a href=\"/rest/repo/file/" + FOLDER + "/what?.pdf\">x</a> /rest/repo/file/" + FOLDER + "/it's.pdf"));
    }

    @Test
    void aTextWithoutALinkHasNone() {
        assertEquals(List.of(), RepoPaths.fileLinkPaths(null));
        assertEquals(List.of(), RepoPaths.fileLinkPaths("https://example.org/efs.pdf"));
        assertEquals(List.of(), RepoPaths.fileLinkPaths("/rest/repo/file/"), "the prefix alone names nothing");
    }

    /** A row's path is stored decoded and a link's is encoded, so a reference may mean either */
    @Test
    void aReferenceNamesItsSpellingAsWrittenAndDecoded() {
        assertEquals(Set.of(FOLDER + "/efs%20uge%2036.pdf", FOLDER + "/efs uge 36.pdf"),
                RepoPaths.candidates(FOLDER + "/efs%20uge%2036.pdf"));
        assertEquals(Set.of(FOLDER + "/nm.pdf"), RepoPaths.candidates(FOLDER + "//./nm.pdf"), "normalised, once");
    }

    /**
     * The repository endpoint decodes a request path as a path, where a '+' stays a '+', so a link to "NtM 1+2.pdf"
     * written as encodeURI writes it ("NtM%201+2.pdf") names that row; a form decode alone would read it as
     * "NtM 1 2.pdf", and the import's verification would report the link as naming no row. A link written with '+'
     * for a space still names the row with the space.
     */
    @Test
    void aPlusInALinkIsAPlusInTheRowItNames() {
        assertEquals(List.of(FOLDER + "/NtM%201+2.pdf", FOLDER + "/NtM 1+2.pdf", FOLDER + "/NtM 1 2.pdf"),
                List.copyOf(RepoPaths.candidates(FOLDER + "/NtM%201+2.pdf")),
                "as written, decoded as a path, decoded as a form value");
        assertTrue(RepoPaths.candidates(FOLDER + "/NtM+1+2.pdf").contains(FOLDER + "/NtM 1 2.pdf"));
        assertEquals(Set.of(FOLDER + "/100%"), RepoPaths.candidates(FOLDER + "/100%"),
                "a spelling that cannot be decoded is left out; the one as written is a name");
    }

    @Test
    void aReferenceThatIsNoRepositoryPathNamesNothing() {
        assertEquals(Set.of(), RepoPaths.candidates(null));
        assertEquals(Set.of(), RepoPaths.candidates("/etc/passwd"));
        assertEquals(Set.of(), RepoPaths.candidates("../../etc/passwd"));
        assertEquals(Set.of("%2F..%2F..%2Fetc%2Fpasswd"), RepoPaths.candidates("%2F..%2F..%2Fetc%2Fpasswd"),
                "the spelling as written is a name; the one that climbs once decoded is left out");
        assertEquals(Set.of("%2E%2E/" + FOLDER + "/a.pdf"), RepoPaths.candidates("%2E%2E/" + FOLDER + "/a.pdf"),
                "the spelling that climbs once decoded is left out; the one as written is a name");
    }
}
