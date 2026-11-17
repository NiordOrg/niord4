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
package org.niord.core.mail;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.niord.model.message.AttachmentDescVo;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessageVo;
import org.niord.model.message.ReferenceDescVo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cleans the HTML that users write before it goes into a mail: the text of a comment, and the texts of a
 * message in a mailing-list mail, the mail dialog's mail and a comment mail.
 * <p>
 * The mail templates print these texts unescaped, so only harmless markup may reach them. This class holds the
 * two allow-lists: {@link #COMMENT_SAFELIST} for a comment, and {@link #MESSAGE_SAFELIST}, which also keeps a
 * message's own formatting (the message-HTML classes and the application's own images). Neither keeps a
 * script, an event handler, a style or a resource from another origin. The templates' own markup (layout,
 * style sheets, attachment images) is trusted and left alone, and the values a template prints into an
 * attachment image's attributes are checked. Only the mail is cleaned: the stored comment and the stored
 * message keep what their authors wrote.
 */
public final class MailHtml {

    /**
     * The markup a comment keeps in a mail: formatting (paragraphs, line breaks, bold and italic, lists,
     * headings, tables, links with an http, https, ftp or mailto address), and no scripts, event handlers,
     * styles, classes or images.
     */
    private static final Safelist COMMENT_SAFELIST = Safelist.relaxed().removeTags("img");

    /**
     * The classes a message text keeps in a mail, by the element that carries them. The names are the
     * message-HTML class vocabulary of the frontends, {@code MESSAGE_HTML_CLASSES} in
     * {@code fe/shared-html/message-html-classes.ts}: the rich-text editor's table, row and cell classes, the
     * position lists and tables the geometry templates write, and the no-wrap span. The mail style sheet
     * {@code common.css} styles them. {@code MailHtmlTest} fails when the names here and there drift apart.
     */
    static final Map<String, Set<String>> MESSAGE_CLASSES = Map.of(
            "table", Set.of("condensed", "no-border", "position-table", "positions"),
            "ul", Set.of("positions"),
            "tr", Set.of("underline"),
            "td", Set.of("pos-col", "pos-index", "underline"),
            "th", Set.of("pos-col", "pos-index", "underline"),
            "span", Set.of("no-wrap"));

    /** The attributes of an image that must be a plain CSS length, or go */
    private static final List<String> IMAGE_SIZES = List.of("width", "height");

    /**
     * The markup a message text keeps in a mail: the formatting of {@link #COMMENT_SAFELIST}, plus the classes
     * of {@link #MESSAGE_CLASSES} on their elements and an image with its source, alternative text, width and
     * height. {@link #cleanMessageText} narrows the classes and the images first.
     */
    private static final Safelist MESSAGE_SAFELIST = messageSafelist();

    /**
     * The width or height of an attachment or an image that a mail keeps: a plain CSS length, as the editor's
     * size fields suggest (a number with an optional unit).
     */
    private static final Pattern CSS_LENGTH =
            Pattern.compile("\\s*\\d+(\\.\\d*)?\\s*(em|px|%|cm|mm|in|pt|pc)?\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * The characters that could end or alter the attribute an attachment's path is printed into. The repository
     * writes a path URL-encoded, so a path it wrote holds none of them.
     */
    private static final char[] ATTRIBUTE_MARKUP = { '"', '<', '>', '&' };

    private MailHtml() {
    }

    private static Safelist messageSafelist() {
        Safelist safelist = Safelist.relaxed().removeAttributes("img", "align", "title");
        MESSAGE_CLASSES.keySet().forEach(tag -> safelist.addAttributes(tag, "class"));
        return safelist;
    }

    /**
     * How a cleaned text is written. Characters are escaped the XHTML way, so a no-break space is written
     * {@code &#xa0;}: a template may upper-case the text (the SafetyNET mail does), and {@code &NBSP;} is not a
     * character reference.
     */
    private static Document.OutputSettings outputSettings() {
        return new Document.OutputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml);
    }

    /**
     * Cleans the HTML of a comment for a mail with {@link #COMMENT_SAFELIST}.
     * <p>
     * Relative links are made absolute against the application's base URI: a repository file is linked
     * root-relative, the mail is read elsewhere, and the allow-list keeps a link only with an allowed protocol.
     * Without a base URI a relative link cannot be resolved and is dropped. Blank HTML is returned as it is.
     *
     * @param html the HTML a user wrote
     * @param baseUri the application's base URI
     * @return the cleaned HTML
     */
    public static String cleanComment(String html, String baseUri) {
        if (StringUtils.isBlank(html)) {
            return html;
        }
        return Jsoup.clean(html, StringUtils.defaultString(baseUri), COMMENT_SAFELIST, outputSettings());
    }

    /**
     * Cleans a text of a message for a mail with {@link #MESSAGE_SAFELIST}.
     * <p>
     * It removes what {@link #cleanComment} removes, except the message's own formatting: a class of
     * {@link #MESSAGE_CLASSES} stays on the element it is meant for, and any other class name goes; an image
     * stays when its source is on the origin of the base URI (root-relative, or absolute on that origin), with
     * its alternative text, and its width and height when they are plain CSS lengths. Any other image goes —
     * from another origin, a {@code data:} image, or any image when there is no base URI. Relative links and
     * image sources are made absolute against the base URI.
     *
     * @param html the HTML of a message text
     * @param baseUri the application's base URI
     * @return the cleaned HTML
     */
    public static String cleanMessageText(String html, String baseUri) {
        if (StringUtils.isBlank(html)) {
            return html;
        }
        Document dirty = Jsoup.parseBodyFragment(html, StringUtils.defaultString(baseUri));

        String ownOrigin = ownOrigin(baseUri);
        for (Element image : dirty.body().select("img")) {
            if (ownOrigin == null || !image.absUrl("src").regionMatches(true, 0, ownOrigin, 0, ownOrigin.length())) {
                image.remove();
                continue;
            }
            IMAGE_SIZES.stream()
                    .filter(size -> image.hasAttr(size) && !isCssLength(image.attr(size)))
                    .forEach(image::removeAttr);
        }

        for (Element element : dirty.body().select("[class]")) {
            Set<String> vocabulary = MESSAGE_CLASSES.getOrDefault(element.normalName(), Set.of());
            String kept = element.classNames().stream()
                    .filter(vocabulary::contains)
                    .collect(Collectors.joining(" "));
            if (kept.isEmpty()) {
                element.removeAttr("class");
            } else {
                element.attr("class", kept);
            }
        }

        Document clean = new Cleaner(MESSAGE_SAFELIST).clean(dirty);
        clean.outputSettings(outputSettings());
        return clean.body().html();
    }

    /**
     * The origin of the base URI followed by a slash, lower-cased, such as {@code https://niord.example.com/}:
     * an absolute address that starts with it points to the application itself, since the authority of an
     * address ends at the first slash. Null when the base URI is blank or not an http(s) address.
     */
    private static String ownOrigin(String baseUri) {
        if (StringUtils.isBlank(baseUri)) {
            return null;
        }
        try {
            URI uri = new URI(baseUri.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Cleans, in place, the parts of a message that the mail templates print unescaped, each with
     * {@link #cleanMessageText}: of every language, the title, vicinity, publications and source, the subject
     * and details of each part, the description of each reference and the caption of each attachment. It also
     * checks the path, width and height of each attachment, which go into the attributes of its image: an
     * attachment whose path holds a character that could leave the attribute is left out, and a width or height
     * that is not a plain CSS length is dropped. Cleaning twice gives what cleaning once gives. Pass a value
     * object made for the mail, never one that is stored or cached.
     *
     * @param message the message value object to clean
     * @param baseUri the application's base URI
     * @return the message, for chaining
     */
    public static <M extends MessageVo> M cleanMessage(M message, String baseUri) {
        if (message == null) {
            return null;
        }
        forEach(message.getDescs(), (MessageDescVo desc) -> {
            desc.setTitle(cleanMessageText(desc.getTitle(), baseUri));
            desc.setVicinity(cleanMessageText(desc.getVicinity(), baseUri));
            desc.setPublication(cleanMessageText(desc.getPublication(), baseUri));
            desc.setInternalPublication(cleanMessageText(desc.getInternalPublication(), baseUri));
            desc.setSource(cleanMessageText(desc.getSource(), baseUri));
        });
        forEach(message.getParts(), part -> forEach(part.getDescs(), (MessagePartDescVo desc) -> {
            desc.setSubject(cleanMessageText(desc.getSubject(), baseUri));
            desc.setDetails(cleanMessageText(desc.getDetails(), baseUri));
        }));
        forEach(message.getReferences(), ref -> forEach(ref.getDescs(),
                (ReferenceDescVo desc) -> desc.setDescription(cleanMessageText(desc.getDescription(), baseUri))));
        if (message.getAttachments() != null) {
            message.setAttachments(message.getAttachments().stream()
                    .filter(att -> !StringUtils.containsAny(att.getPath(), ATTRIBUTE_MARKUP))
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
        forEach(message.getAttachments(), att -> {
            att.setWidth(cssLength(att.getWidth()));
            att.setHeight(cssLength(att.getHeight()));
            forEach(att.getDescs(),
                    (AttachmentDescVo desc) -> desc.setCaption(cleanMessageText(desc.getCaption(), baseUri)));
        });
        return message;
    }

    /** Returns a width or height when it is blank or a plain CSS length, and null otherwise */
    private static String cssLength(String value) {
        return StringUtils.isBlank(value) || isCssLength(value) ? value : null;
    }

    /** Whether a value is a plain CSS length */
    private static boolean isCssLength(String value) {
        return value != null && CSS_LENGTH.matcher(value).matches();
    }

    /** Applies the action to each element of a list that may be null */
    private static <T> void forEach(List<T> list, Consumer<T> action) {
        if (list != null) {
            list.forEach(action);
        }
    }
}
