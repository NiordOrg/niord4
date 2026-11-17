/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * What a publication's link may be: an address a reader can follow.
 *
 * Two shapes, as the documents give them: an absolute http or https address (an
 * external document), or a path under {@code /rest/repo/} (a file the repository
 * hosts). Anything else -- a word, a {@code javascript:} address, another scheme,
 * an address with no host -- used to be stored as typed and published to readers
 * as a dead link. The link is also written into a message's publication HTML
 * inside {@code href="..."}, so a double quote in it broke out of the attribute.
 *
 * One rule for both writers, the issue page's link box and the one-off form, so
 * the two cannot accept different things. A link already stored is never
 * rewritten; it is checked only when it is saved again.
 */
public final class IssueLinkRule {

    /** The refusal's code. Declared here so the catalogue scan resolves it. */
    public static final String LINK_INVALID = "LINK_INVALID";

    /** Where the repository serves its files; a hosted document's link starts with it. */
    static final String REPO_PATH_PREFIX = "/rest/repo/";

    private IssueLinkRule() {
    }

    /**
     * The link to store: null for a blank one, otherwise the trimmed link when it
     * is an address a reader can follow.
     *
     * @throws IssueLifecycleService.TransitionRefusedException with {@link #LINK_INVALID}
     *         when it is not
     */
    public static String validated(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String trimmed = link.trim();
        if (isRepositoryPath(trimmed) || isWebAddress(trimmed)) {
            return trimmed;
        }
        throw new IssueLifecycleService.TransitionRefusedException(LINK_INVALID,
                "'" + trimmed + "' is not a link a reader can follow: give an http or https address, "
                        + "or a repository path");
    }

    /**
     * A path the repository serves. Spaces are allowed, because an uploaded
     * file's name may hold them and its derived address carries the name as it
     * is; a double quote, an angle bracket or a control character is not, since
     * the link is written into an HTML attribute.
     */
    private static boolean isRepositoryPath(String link) {
        if (!link.startsWith(REPO_PATH_PREFIX) || link.length() == REPO_PATH_PREFIX.length()) {
            return false;
        }
        for (int i = 0; i < link.length(); i++) {
            char c = link.charAt(i);
            if (c == '"' || c == '<' || c == '>' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * An absolute http or https address with a host. java.net.URI refuses a
     * space, a double quote and an angle bracket, so such a link fails the parse.
     */
    private static boolean isWebAddress(String link) {
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null && !host.isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
