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
package org.niord.core.repo;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.util.TextUtils;
import org.niord.core.util.WebUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repository paths as strings: the path a file has in the store, which is also the path in its URL
 * ({@code /rest/repo/file/<path>}) and in the rows that name it.
 * <p>
 * A repository path is relative, uses forward slashes and names no {@code .} or {@code ..} segment. Every path a
 * request names is normalised with {@link #normalize} before it is used, which collapses those segments and refuses
 * an absolute path and one that climbs out of the repository; the checks are lexical, so a path need not exist. The
 * served roots are the folders {@code /rest/repo/*} answers for; the archive of superseded publication editions is a
 * folder of the store that no public endpoint serves.
 */
public final class RepoPaths {

    /** The root of the edit sessions, uploads and extracted archives; its rows expire */
    public static final String TEMP_ROOT = "temp";

    /** The root of the message folders */
    public static final String MESSAGES_ROOT = "messages";

    /** The root of the publication folders, the issues' and the legacy publications' alike */
    public static final String PUBLICATIONS_ROOT = "publications";

    /** The root of the superseded publication editions, never served by {@code /rest/repo/*} */
    public static final String ARCHIVE_ROOT = "archive";

    /** The roots {@code /rest/repo/*} serves */
    private static final Set<String> SERVED_ROOTS = Set.of(MESSAGES_ROOT, PUBLICATIONS_ROOT, TEMP_ROOT);

    /** The start of a served file's URL, {@code /rest/repo/file/<path>}: how a link in a text or a row names a file */
    public static final String FILE_URL_PREFIX = "/rest/repo/file/";

    /**
     * A served file's link in a text: the prefix and the path after it, which ends at a quote or an angle bracket
     * (the attribute or tag around a link in HTML), at white space, and at a query string or a fragment, which the
     * endpoint does not see as the path. A parenthesis is part of a name ({@link
     * org.niord.core.util.WebUtils#encodeURI} keeps it), so it does not end a link.
     */
    private static final Pattern FILE_LINK = Pattern.compile(Pattern.quote(FILE_URL_PREFIX) + "([^\"'<>\\s?#]+)");

    private RepoPaths() {
    }

    /**
     * Normalises a repository-relative path: collapses {@code .} and empty segments, resolves {@code ..} against
     * the segments before it, and turns backslashes into slashes.
     *
     * @param path the path, usually from a request
     * @return the normalised path, {@code ""} for the root; null when the path is null, absolute, holds a NUL
     *         character, or climbs out of the repository
     */
    public static String normalize(String path) {
        if (path == null || path.indexOf('\0') >= 0) {
            return null;
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || (p.length() > 1 && p.charAt(1) == ':')) {
            return null;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return String.join("/", segments);
    }

    /**
     * Whether a normalised path lies at or below a normalised folder. The root ({@code ""}) holds everything.
     *
     * @param path the path
     * @param folder the folder
     * @return whether the path is the folder or lies below it
     */
    public static boolean isWithin(String path, String folder) {
        if (path == null || folder == null) {
            return false;
        }
        return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
    }

    /** Whether a normalised path lies strictly below a normalised folder */
    public static boolean isBelow(String path, String folder) {
        return isWithin(path, folder) && !path.equals(folder);
    }

    /** Whether a normalised path is the temp root or lies below it */
    public static boolean isTemp(String path) {
        return isWithin(path, TEMP_ROOT);
    }

    /**
     * Whether a normalised path lies strictly below one of the roots {@code /rest/repo/*} serves. A root itself
     * is not served: it is nobody's folder, and a folder operation on it would reach every message's or every
     * publication's files.
     */
    public static boolean isServed(String path) {
        return !StringUtils.isBlank(path) && path.indexOf('/') > 0 && SERVED_ROOTS.contains(rootOf(path));
    }

    /**
     * Normalises a path and keeps it only when it lies strictly below a served root: what a request or a stored
     * reference may name.
     *
     * @param path the path, usually from a request or a row
     * @return the normalised path, or null when it is not a served one
     */
    public static String served(String path) {
        String normalized = normalize(path);
        return normalized != null && isServed(normalized) ? normalized : null;
    }

    /**
     * The paths of the served file links in a text, as written: the path after each {@link #FILE_URL_PREFIX} the
     * text holds, cut as {@link #FILE_LINK} says. One rule for everything that reads a link back to a row: the
     * import's verification and the files a legacy publication's links keep from a deleted issue.
     *
     * @param text a text, a link field or HTML; null for none
     * @return the paths as written, in the order of the text; empty when the text holds no link
     */
    public static List<String> fileLinkPaths(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        Matcher m = FILE_LINK.matcher(text);
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    /**
     * The store paths a stored reference may name: the reference as written and URL-decoded, each normalised. A
     * row's path is stored as it is, a link's path is encoded, and a reference the database holds may be either;
     * a spelling that is no path of the repository (absolute, climbing, the root) is left out.
     *
     * @param reference a stored path or a link's path; null for none
     * @return the paths it may name, the spelling as written first; empty when it names none
     */
    public static Set<String> candidates(String reference) {
        Set<String> candidates = new LinkedHashSet<>();
        if (reference == null) {
            return candidates;
        }
        for (String spelling : List.of(reference, WebUtils.urlDecode(reference))) {
            String path = normalize(spelling);
            if (path != null && !path.isEmpty()) {
                candidates.add(path);
            }
        }
        return candidates;
    }

    /** The first segment of a path: its top-level folder, or the path itself when it has one segment */
    public static String rootOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /**
     * Whether a repository-relative path, once normalised, names something strictly below a repository-relative
     * folder, once normalised. The comparison is lexical, so neither needs to exist: it is meant for paths that are
     * stored now and resolved against the repository later. An absolute path, a path that climbs out of the
     * repository, and the root as the folder are never a match.
     *
     * @param path the repository-relative path
     * @param folder the repository-relative folder
     * @return whether the path lies strictly below the folder
     */
    public static boolean isBelowFolder(String path, String folder) {
        if (StringUtils.isBlank(path) || StringUtils.isBlank(folder)) {
            return false;
        }
        String p = normalize(path);
        String f = normalize(folder);
        return p != null && f != null && !f.isEmpty() && isBelow(p, f);
    }

    /** The parent folder of a path, {@code ""} for a path directly under the root */
    public static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** The last segment of a path */
    public static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The path of a file directly in a folder; the root folder gives the bare name */
    public static String join(String folder, String name) {
        return folder.isEmpty() ? name : folder + "/" + name;
    }

    /**
     * The {@code LIKE} pattern that matches every path strictly below a folder, the folder's own wildcards escaped
     * as {@link TextUtils#escapeLike} does, for {@code ESCAPE '!'}.
     */
    static String likePrefix(String folder) {
        return TextUtils.escapeLike(folder) + "/%";
    }
}
