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

package org.niord.core.publication;

/**
 * Whether an id can be served as one segment of an address.
 *
 * <p>A publication category's id and a publication series' id are the last segment of every address that reads,
 * changes or deletes the record ({@code /rest/publication-categories/publication-category/{categoryId}},
 * {@code /rest/publication-series/series/{seriesId}}, {@code /rest/one-off-publications/{seriesId}}). The request
 * firewall ({@code EncodedSlashConfig}, Spring Security's strict firewall) refuses with 400, before any handler runs,
 * every such address whose segment holds a slash, a back slash, a percent sign, a semicolon, a line break or another
 * control character, or a line or paragraph separator, and a segment of {@code .} or {@code ..} is read as a step in
 * the path. A record stored under such an id could therefore never be opened, changed or deleted (PUBCAT2).
 *
 * <p>The rule refuses exactly that and nothing more. It is deliberately no slug pattern: the ids deployments and
 * countries already hold have not been read, and a stricter rule could refuse an import that works today.
 */
public final class PathSegmentId {

    private PathSegmentId() {
    }

    /**
     * Why the id cannot be served as one path segment, or null when it can.
     *
     * <p>A null id gives null: whether an id is required is the caller's rule, not this one's.
     *
     * @param id the id as it would be stored
     * @return a sentence fragment naming what the firewall refuses, or null
     */
    public static String unservableReason(String id) {
        if (id == null) {
            return null;
        }
        if (".".equals(id) || "..".equals(id)) {
            return "an id of '" + id + "' is read as a step in the address, not as a name";
        }
        for (int i = 0; i < id.length(); ) {
            int cp = id.codePointAt(i);
            if (cp == '/' || cp == '\\' || cp == '%' || cp == ';') {
                return "it holds '" + Character.toString(cp) + "'";
            }
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
                return "it holds a control character or a line break";
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    /**
     * The whole refusal sentence for the record named by the noun, or null when the id can be served.
     *
     * @param noun what the id names, as in "categoryId" or "seriesId"
     * @param id   the id as it would be stored
     */
    public static String refusal(String noun, String id) {
        String reason = unservableReason(id);
        if (reason == null) {
            return null;
        }
        return noun + " '" + id + "' cannot be used: " + reason + ". The server refuses every address whose id"
                + " holds /, \\, %, ; or a control character, or is . or .., so the record could never be"
                + " opened, changed or deleted";
    }
}
