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

import org.apache.commons.lang3.StringUtils;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The links to a message that the backend sends out in mails and tweets.
 *
 * <p>A message link has the form {@code <linkBaseUri>/messages?messageId=<id>}, where the link base URI is
 * {@code NiordApp.getMessageLinkBaseUri()}: the administration web app, or {@code baseUri} when none is
 * configured. The bootstrap's {@code message-support.ftl} and the default tweet format build that form.
 * The templates and tweet formats a deployment holds in its database may differ from them (an older or
 * edited {@code ScriptResource} row, which is what {@code FmTemplateLoader} serves), and those may build the
 * hash-routed form {@code <baseUri>/#/message/<id>}. {@link #rewriteHashLinks(String, String, String)}
 * turns that form into the first one, so every link sent out has the same form whichever template or
 * format produced it.
 */
public final class MessageLinks {

    /** The path and fragment between the base URI and the id in a hash-routed message link */
    private static final String HASH_LINK = "#/message/";

    /** The id of a hash-routed message link runs to the next quote, tag start, whitespace or the end */
    private static final String HASH_LINK_ID = "([^\"'<\\s]+)";

    private MessageLinks() {
    }

    /**
     * Rewrites every hash-routed message link {@code <baseUri>/#/message/<id>} in the text into
     * {@code <linkBaseUri>/messages?messageId=<id>}, with the id URL-encoded as a query value. Everything
     * else in the text is left as it is.
     *
     * @param text the text, such as a rendered mail or a tweet
     * @param baseUri the base URI the hash-routed links were built from
     * @param linkBaseUri the base URI message links point at
     * @return the text with its hash-routed message links rewritten
     */
    public static String rewriteHashLinks(String text, String baseUri, String linkBaseUri) {
        if (StringUtils.isEmpty(text) || StringUtils.isBlank(baseUri)) {
            return text;
        }

        // A base URI with a trailing slash has built links with a double slash before the fragment
        Pattern hashLink = Pattern.compile(
                Pattern.quote(StringUtils.stripEnd(baseUri, "/")) + "/+" + Pattern.quote(HASH_LINK) + HASH_LINK_ID);
        String messageLink = StringUtils.stripEnd(StringUtils.defaultString(linkBaseUri), "/") + "/messages?messageId=";

        return hashLink.matcher(text)
                .replaceAll(link -> Matcher.quoteReplacement(messageLink + queryValue(link.group(1))));
    }

    /**
     * Encodes the id of a hash-routed link as a query value. The id is percent-decoded first, so an id the
     * link carried encoded is not encoded twice; one with a malformed escape is taken as written.
     */
    private static String queryValue(String id) {
        String decoded;
        try {
            // Percent-decoding only: a '+' in a URL fragment is a plus sign, not a space
            decoded = URLDecoder.decode(id.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = id;
        }
        // Every character but letters, digits and "-_.*" is escaped, so the value cannot end a quoted attribute
        return URLEncoder.encode(decoded, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
