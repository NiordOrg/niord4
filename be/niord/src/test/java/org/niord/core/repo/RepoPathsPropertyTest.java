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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the repository paths ({@link RepoPaths}): every path a request names goes through
 * {@link RepoPaths#normalize} and {@link RepoPaths#served} before a file is read, written or deleted. Paths generated
 * from a fixed seed out of the segments a request may hold: names with Danish letters, spaces and dots, ".", "..",
 * empty segments, back slashes, encoded dots and slashes, a drive letter.
 */
public class RepoPathsPropertyTest {

    private static final long SEED = 20261115L;

    private static final String[] SEGMENTS = {
            "messages", "publications", "temp", "archive", "a", "b1", "ab", "Ærø øst.pdf", "file.txt", "..", ".", "",
            "...", "..a", "a..", "%2e%2e", "%2E%2E%2F", "C:", "c:", "x:y.pdf", "name with space", "€", "a\\b", "\\",
            ".hidden", "ø", "(1)", "%20", "a+b.pdf", "what?.pdf", "#1.pdf", "it's.pdf"
    };

    private static String path(Random r) {
        int n = 1 + r.nextInt(6);
        StringBuilder sb = new StringBuilder();
        if (r.nextInt(8) == 0) {
            sb.append(r.nextBoolean() ? "/" : "\\");
        }
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(r.nextInt(6) == 0 ? "\\" : "/");
            }
            sb.append(SEGMENTS[r.nextInt(SEGMENTS.length)]);
        }
        if (r.nextInt(6) == 0) {
            sb.append('/');
        }
        return sb.toString();
    }

    /** The root the reference resolver resolves against; no name of a generated path's segment can be "r" */
    private static final Path REFERENCE_ROOT = Path.of("/r");

    /**
     * What a path names, by a resolver that is not the product's: {@link Path#resolve} and {@link Path#normalize}
     * of the platform, below the root {@code /r}. A back slash separates as a slash does (as {@link RepoPaths}
     * reads it). Each name is replaced by a token ("n0", "n1", ...) before the platform sees it and put back
     * after, so the platform's own rules for a name (":" and "?" on Windows, a trailing dot) play no part, and a
     * path that climbs above {@code /r} and comes back down cannot meet {@code r} again.
     *
     * @return {@code Optional.empty()} when the path climbs above {@code /r} or is absolute; else the path below
     *         {@code /r} it names, {@code ""} for {@code /r} itself
     */
    private static Optional<String> reference(String p) {
        String q = p.replace('\\', '/');
        List<String> names = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (String segment : q.split("/")) {
            if (segment.isEmpty()) {
                continue; // "a//b" is "a/b"; and "//x" would be a UNC path on Windows
            }
            if (segment.equals(".") || segment.equals("..")) {
                tokens.add(segment);
            } else {
                tokens.add("n" + names.size());
                names.add(segment);
            }
        }
        String tokenPath = (q.startsWith("/") ? "/" : "") + String.join("/", tokens);
        Path resolved = REFERENCE_ROOT.resolve(tokenPath).normalize();
        if (!resolved.startsWith(REFERENCE_ROOT)) {
            return Optional.empty();
        }
        List<String> below = new ArrayList<>();
        for (Path token : REFERENCE_ROOT.relativize(resolved)) {
            if (!token.toString().isEmpty()) {
                below.add(names.get(Integer.parseInt(token.toString().substring(1))));
            }
        }
        return Optional.of(String.join("/", below));
    }

    /** A path RepoPaths refuses as absolute on Windows: a character and a colon first ("C:", "x:y.pdf") */
    private static boolean startsLikeADrive(String p) {
        return p.length() > 1 && p.charAt(1) == ':';
    }

    /** Whether a path below the repository is one of the served ones: strictly below messages, publications or temp */
    private static boolean isBelowAServedRoot(String n) {
        int slash = n.indexOf('/');
        return slash > 0 && List.of("messages", "publications", "temp").contains(n.substring(0, slash));
    }

    @Test
    public void theReferenceResolver_theSmallestInputs() {
        // The reference itself, on the inputs the record names (RepoCacheTest and RepoFileLinksTest hold the same)
        assertEquals(Optional.of("temp/x"), reference("messages/a/../../temp/x"));
        assertEquals(Optional.empty(), reference("../messages/x"));
        assertEquals(Optional.empty(), reference("a/../../r/x"));
        assertEquals(Optional.empty(), reference("/messages/x"));
        assertEquals(Optional.empty(), reference("\\messages\\x"));
        assertEquals(Optional.of(""), reference("a/.."));
        assertEquals(Optional.of("messages/what?.pdf"), reference("messages\\.\\what?.pdf"));
        // And the product on them
        assertEquals("temp/x", RepoPaths.normalize("messages/a/../../temp/x"));
        assertNull(RepoPaths.normalize("../messages/x"));
        assertNull(RepoPaths.served("../messages/x"));
        assertNull(RepoPaths.normalize("a/../../r/x"));
    }

    @Test
    public void aNormalisedPath_namesTheSameFileAsAReference_isRelative_andNormalisesToItself() {
        // The normalised path names the file the path names, by a resolver that is not RepoPaths' (reference above);
        // a path that climbs above the repository's root, also only on the way ("a/../../r/x"), or is absolute, is
        // refused. Mutation M2 (a ".." ignored instead of resolved or refused) fails this test.
        Random random = new Random(SEED);
        List<String> notStable = new ArrayList<>();
        int refused = 0;
        int named = 0;
        for (int i = 0; i < 20000; i++) {
            String p = path(random);
            String n = RepoPaths.normalize(p);
            Optional<String> expected = reference(p);
            if (n == null) {
                // Refused: the reference agrees that it climbs or is absolute, or it starts like a drive
                assertTrue(expected.isEmpty() || startsLikeADrive(p.replace('\\', '/')), "\"" + p
                        + "\" is refused, but names \"" + expected.orElse(null) + "\"");
                refused++;
                continue;
            }
            named++;
            assertEquals(expected.orElse(null), n, "\"" + p + "\" names another file");
            assertFalse(n.startsWith("/"), p + " -> " + n);
            assertFalse(n.contains("\\"), p + " -> " + n);
            assertTrue(n.isEmpty() || Arrays.stream(n.split("/", -1))
                    .noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), p + " -> " + n);
            // Idempotent: what normalize gave is a path normalize gives back unchanged
            String again = RepoPaths.normalize(n);
            if (!n.equals(again)) {
                notStable.add("\"" + p + "\" -> \"" + n + "\" -> " + (again == null ? "refused" : "\"" + again + "\""));
            }
        }
        // Not idempotent in one case: a path whose drive-letter-like segment comes second ("./C:/x", "a/../c:/x")
        // is normalised to "C:/x", which normalize itself refuses as absolute. Pinned as found; see
        // aDriveLetterAfterADotSegment (not reachable; no finding).
        assertTrue(notStable.stream().allMatch(s -> s.contains(":")), "" + notStable);
        assertTrue(refused > 1000 && named > 1000, "too few of a kind to mean anything: " + refused + " refused, "
                + named + " named");
    }

    @Test
    public void aDriveLetterAfterADotSegment_isNotRefused_documentsBehavior() {
        // The smallest input. normalize refuses "C:/x" (a drive letter: absolute on Windows) but accepts
        // "./C:/x" and returns "C:/x". Not reachable as a served path (served() requires a served root first, and
        // "C:" is none), and the store's rows are keys, not files; the cache folder (RepoCache) resolves only served
        // paths. Pinned so a change shows.
        assertEquals(null, RepoPaths.normalize("C:/x"));
        assertEquals("C:/x", RepoPaths.normalize("./C:/x"));
        assertEquals("C:/x", RepoPaths.normalize("a/../C:/x"));
        assertEquals(null, RepoPaths.served("./C:/x"));
        assertEquals("messages/C:/x", RepoPaths.served("messages/C:/x"));
    }

    @Test
    public void aServedPath_namesTheSameFileAsAReference_staysBelowAServedRoot_andNeverClimbs() {
        // Served is the reference's path when that lies strictly below messages, publications or temp, and null
        // otherwise: when the path climbs above the root (also only on the way), is absolute or starts like a drive.
        Random random = new Random(SEED + 1);
        int served = 0;
        int climbing = 0;
        for (int i = 0; i < 20000; i++) {
            String p = path(random);
            String s = RepoPaths.served(p);
            Optional<String> expected = reference(p);
            if (expected.isEmpty()) {
                climbing++;
                assertNull(s, "climbs above the root or is absolute, but is served: \"" + p + "\" -> \"" + s + "\"");
            } else if (startsLikeADrive(p.replace('\\', '/'))) {
                assertNull(s, "starts like a drive, but is served: \"" + p + "\" -> \"" + s + "\"");
            } else {
                assertEquals(isBelowAServedRoot(expected.get()) ? expected.get() : null, s,
                        "\"" + p + "\" names \"" + expected.get() + "\"");
            }
            if (s == null) {
                continue;
            }
            served++;
            String root = RepoPaths.rootOf(s);
            assertTrue(root.equals("messages") || root.equals("publications") || root.equals("temp"), p + " -> " + s);
            assertTrue(s.indexOf('/') > 0, p + " -> " + s);
            assertFalse(Arrays.asList(s.split("/")).contains(".."), p + " -> " + s);
            assertEquals(s, RepoPaths.served(s), p);
            // Every candidate a stored reference may name, decoded or not, stays inside the repository
            for (String c : RepoPaths.candidates(p)) {
                assertFalse(c.startsWith("/") || Arrays.asList(c.split("/")).contains(".."), p + " -> " + c);
            }
        }
        assertTrue(served > 1000 && climbing > 1000, "too few of a kind to mean anything: " + served + " served, "
                + climbing + " climbing");
    }

    @Test
    public void joinAndSplit_areInverse_andWithinAgreesWithTheSegments() {
        Random random = new Random(SEED + 2);
        for (int i = 0; i < 20000; i++) {
            String folder = RepoPaths.normalize(path(random));
            String name = SEGMENTS[random.nextInt(SEGMENTS.length)];
            if (folder == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")
                    || name.contains("\\")) {
                continue;
            }
            String joined = RepoPaths.join(folder, name);
            assertEquals(folder, RepoPaths.folderOf(joined), joined);
            assertEquals(name, RepoPaths.nameOf(joined), joined);
            assertTrue(RepoPaths.isWithin(joined, folder), joined);
            assertTrue(RepoPaths.isBelow(joined, folder), joined);
            assertFalse(RepoPaths.isBelow(folder, joined), joined);
            // A sibling whose name starts like the folder is not inside it ("ab" is not inside "a")
            if (!folder.isEmpty()) {
                assertFalse(RepoPaths.isWithin(folder + "x/" + name, folder), folder);
            }
        }
    }

    /**
     * The stored paths whose link, read back from a text, does not name them
     *
     * @param written collects every stored path a link was written for
     */
    private static List<String> linksNotReadBack(Random random, int texts, List<String> written) {
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < texts; i++) {
            List<String> stored = new ArrayList<>();
            StringBuilder html = new StringBuilder("<p>");
            int links = 1 + random.nextInt(3);
            for (int j = 0; j < links; j++) {
                String p = RepoPaths.served("messages/" + path(random));
                if (p == null) {
                    continue;
                }
                stored.add(p);
                written.add(p);
                String href = RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI(p);
                html.append(random.nextBoolean() ? "<a href=\"" + href + "\">file</a> " : "<img src=\"" + href + "?t=1\"> ");
            }
            html.append("</p>");
            List<String> found = RepoPaths.fileLinkPaths(html.toString());
            for (int j = 0; j < stored.size(); j++) {
                if (j >= found.size() || !RepoPaths.candidates(found.get(j)).contains(stored.get(j))) {
                    wrong.add(stored.get(j));
                }
            }
        }
        return wrong;
    }

    @Test
    public void everyLinkInAText_isFoundAsWritten_andDecodesToTheStoredPath() {
        // A file's link in a message text is /rest/repo/file/<encoded path> (WebUtils.encodeURI); reading the
        // links back from the text (RepoPaths.fileLinkPaths: the import's check of the files a text links, and the
        // files a legacy publication's links keep from a deleted issue) and decoding them gives the stored paths.
        // REPO4 (PB-6): this held except for a name holding "?" or "'", which encodeURI left raw while the link
        // reader ends a link at either, so "what?.pdf" was read as "what" and "it's.pdf" as "it". An upload keeps
        // such a name (WebUtils.bareFileName). encodeURI now writes both encoded (%3F and %27), so every link reads
        // back whole, also an image's link followed by a query string of its own.
        List<String> written = new ArrayList<>();
        List<String> wrong = linksNotReadBack(new Random(SEED + 3), 3000, written);
        assertEquals(List.of(), wrong);
        // The property means something only if links of both kinds of name were written
        assertTrue(written.stream().anyMatch(p -> p.contains("?")), "no name with a question mark was written");
        assertTrue(written.stream().anyMatch(p -> p.contains("'")), "no name with an apostrophe was written");

        // The smallest inputs give the full path
        List<String> what = RepoPaths.fileLinkPaths(
                "<a href=\"" + RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI("messages/a/b/what?.pdf") + "\">x</a>");
        assertEquals(List.of("messages/a/b/what%3F.pdf"), what);
        assertTrue(RepoPaths.candidates(what.get(0)).contains("messages/a/b/what?.pdf"), "" + what);
        List<String> its = RepoPaths.fileLinkPaths(
                "<a href='" + RepoPaths.FILE_URL_PREFIX + WebUtils.encodeURI("messages/a/b/it's.pdf") + "'>x</a>");
        assertEquals(List.of("messages/a/b/it%27s.pdf"), its);
        assertTrue(RepoPaths.candidates(its.get(0)).contains("messages/a/b/it's.pdf"), "" + its);
    }

    @Test
    public void aBareFileName_hasNoFolder_andIsStable() {
        // An upload's name is reduced to a bare name (WebUtils.bareFileName) before it is joined to a folder: the
        // result holds no slash of either kind, is never "." or "..", and reducing it again changes nothing.
        Random random = new Random(SEED + 4);
        for (int i = 0; i < 20000; i++) {
            String name = path(random) + (random.nextBoolean() ? " " : "");
            String bare = WebUtils.bareFileName(name);
            if (bare == null) {
                continue;
            }
            assertFalse(bare.contains("/") || bare.contains("\\"), name + " -> " + bare);
            assertFalse(bare.isEmpty() || bare.equals(".") || bare.equals(".."), name + " -> " + bare);
            assertEquals(bare, WebUtils.bareFileName(bare), name);
            String joined = RepoPaths.join("messages/a/bc/uid", bare);
            assertEquals(joined, RepoPaths.served(joined), name + " -> " + joined);
        }
    }
}
