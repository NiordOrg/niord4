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

import org.junit.jupiter.api.Test;
import org.niord.model.message.AttachmentDescVo;
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.MessageVo;
import org.niord.model.message.ReferenceDescVo;
import org.niord.model.message.ReferenceVo;
import org.niord.test.CountryBuildGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link MailHtml}: the allow-lists for HTML that users wrote and that goes into a mail. A comment keeps its
 * formatting and loses scripts, event handlers, styles, classes and images. A message text keeps its formatting
 * too, with the message-HTML classes and the application's own images, and loses the rest. Relative links are
 * made absolute against the application's base URI.
 */
@CountryBuildGroup
class MailHtmlTest {

    private static final String BASE_URI = "https://niord.example.com";

    /** The frontends' message-HTML class vocabulary, relative to the repository root */
    private static final String MESSAGE_HTML_CLASSES_TS = "fe/shared-html/message-html-classes.ts";

    /** Message HTML with active content: a script, an event handler, a foreign image and an inline style. */
    private static final String ACTIVE = "<script>alert('it-script')</script>"
            + "<p onclick=\"alert('it-onclick')\" style=\"position:fixed\" class=\"fixed inset-0\">clicked</p>"
            + "<img src=\"https://tracker.example.org/it.png\" onerror=\"alert('it-onerror')\">";

    /** Message HTML with the formatting the rich-text editor writes. */
    private static final String FORMATTING = "<p>IT <strong>bold</strong> and <em>italic</em><br>next line</p>"
            + "<ul><li>first</li><li>second</li></ul>"
            + "<table><tbody><tr><td>cell</td></tr></tbody></table>"
            + "<p><a href=\"https://example.com/x\">a link</a></p>";

    /** A position table the geometry templates write, and the editor's table, row and cell classes. */
    private static final String MESSAGE_CLASSES = "<table class=\"position-table\"><tbody><tr>"
            + "<td class=\"pos-index\">1)</td><td class=\"pos-col\">55° 42,0'N</td></tr></tbody></table>"
            + "<table class=\"positions\"><tbody><tr><td>1)</td><td>55° 42,0'N</td></tr></tbody></table>"
            + "<ul class=\"positions\"><li>55° 42,0'N</li></ul>"
            + "<table class=\"no-border condensed\"><tbody><tr class=\"underline\">"
            + "<th class=\"underline\">head</th><td class=\"underline\">cell</td></tr></tbody></table>"
            + "<p><span class=\"no-wrap\">Skagen Havn</span></p>";

    private static void assertNoActiveContent(String html) {
        assertFalse(html.contains("it-script"), html);
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("it-onclick"), html);
        assertFalse(html.contains("onclick"), html);
        assertFalse(html.contains("it-onerror"), html);
        assertFalse(html.contains("onerror"), html);
        assertFalse(html.contains("tracker.example.org"), html);
        assertFalse(html.contains("style="), html);
        assertFalse(html.contains("position:fixed"), html);
        assertFalse(html.contains("inset-0"), html);
    }

    // ------------------------------------------------------------------------------------------- //
    // A comment
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCommentLosesScriptsEventHandlersImagesStylesAndClasses() {
        String html = MailHtml.cleanComment(ACTIVE, BASE_URI);

        assertNoActiveContent(html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("<p>clicked</p>"), "the text of an element whose attributes go stays: " + html);
    }

    /** The comment keeps the strict list: the message-HTML classes and the application's own images go too. */
    @Test
    void aCommentLosesTheMessageClassesAndTheApplicationsOwnImages() {
        String html = MailHtml.cleanComment("<table class=\"positions\"><tbody><tr><td class=\"pos-col\">1</td>"
                + "</tr></tbody></table><img src=\"/rest/repo/file/messages/a/ab/uid/chart.png\" alt=\"chart\">"
                + "<img src=\"" + BASE_URI + "/rest/repo/file/messages/a/ab/uid/map.png\">", BASE_URI);

        assertEquals("<table><tbody><tr><td>1</td></tr></tbody></table>", html);
    }

    @Test
    void ordinaryFormattingSurvives() {
        assertEquals(FORMATTING, MailHtml.cleanComment(FORMATTING, BASE_URI));
        assertEquals(FORMATTING, MailHtml.cleanMessageText(FORMATTING, BASE_URI));
    }

    @Test
    void aRootRelativeLinkBecomesAbsolute() {
        String link = "<a href=\"/rest/repo/file/messages/a/ab/uid/notice.pdf\">notice</a>";
        String absolute = "<a href=\"" + BASE_URI + "/rest/repo/file/messages/a/ab/uid/notice.pdf\">notice</a>";

        assertEquals(absolute, MailHtml.cleanComment(link, BASE_URI));
        assertEquals(absolute, MailHtml.cleanMessageText(link, BASE_URI));
    }

    @Test
    void withoutABaseUriARelativeLinkIsDroppedButItsTextStays() {
        assertEquals("<a>notice</a>", MailHtml.cleanComment("<a href=\"/rest/repo/file/notice.pdf\">notice</a>", null));
        assertEquals("<a>notice</a>", MailHtml.cleanMessageText("<a href=\"/rest/repo/file/notice.pdf\">notice</a>", null));
    }

    @Test
    void aScriptLinkIsDropped() {
        assertEquals("<a>click</a>", MailHtml.cleanComment("<a href=\"javascript:alert(1)\">click</a>", BASE_URI));
        assertEquals("<a>click</a>", MailHtml.cleanMessageText("<a href=\"javascript:alert(1)\">click</a>", BASE_URI));
    }

    @Test
    void plainTextKeepsItsMeaningAsHtml() {
        assertEquals("Skagen &amp; Hirtshals, depth &lt; 5 m", MailHtml.cleanComment("Skagen & Hirtshals, depth < 5 m", BASE_URI));
        assertEquals("Skagen &amp; Hirtshals, depth &lt; 5 m", MailHtml.cleanMessageText("Skagen & Hirtshals, depth < 5 m", BASE_URI));
        assertEquals("FA/EK-D-371 2017", MailHtml.cleanMessageText("FA/EK-D-371 2017", BASE_URI));
    }

    /**
     * The SafetyNET mail upper-cases the vicinity. {@code &NBSP;} is not a character reference and would show
     * literally; {@code &#XA0;} is one.
     */
    @Test
    void aNoBreakSpaceIsWrittenAsANumericReferenceThatSurvivesUpperCasing() {
        String html = MailHtml.cleanMessageText("Skagen Havn &amp;&nbsp;Hirtshals", BASE_URI);

        assertEquals("Skagen&#xa0;Havn &amp;&#xa0;Hirtshals", html);
        assertFalse(html.toUpperCase().contains("&NBSP;"), html);
        assertEquals("a&#xa0;b", MailHtml.cleanComment("a&nbsp;b", BASE_URI));
    }

    @Test
    void blankHtmlIsReturnedAsItIs() {
        assertNull(MailHtml.cleanComment(null, BASE_URI));
        assertEquals("", MailHtml.cleanComment("", BASE_URI));
        assertEquals("  ", MailHtml.cleanComment("  ", BASE_URI));
        assertNull(MailHtml.cleanMessageText(null, BASE_URI));
        assertEquals("  ", MailHtml.cleanMessageText("  ", BASE_URI));
    }

    // ------------------------------------------------------------------------------------------- //
    // A message text
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageTextLosesScriptsEventHandlersStylesAndForeignImages() {
        String html = MailHtml.cleanMessageText(ACTIVE, BASE_URI);

        assertNoActiveContent(html);
        assertFalse(html.contains("<img"), html);
        assertEquals("<p>clicked</p>", html);
    }

    /** The position tables and the editor's table classes keep their layout in a mail. */
    @Test
    void aMessageTextKeepsTheMessageClassesOnTheirElements() {
        assertEquals(MESSAGE_CLASSES, MailHtml.cleanMessageText(MESSAGE_CLASSES, BASE_URI));
    }

    /**
     * A class name outside the vocabulary goes, and so does a vocabulary name on an element it is not meant for;
     * the names that stay keep their order.
     */
    @Test
    void anyOtherClassIsDropped() {
        String html = MailHtml.cleanMessageText("<table class=\"fixed positions inset-0 z-50\"><tbody>"
                + "<tr class=\"underline bg-red\"><td class=\"pos-col fixed\">1</td><td class=\"z-50\">2</td></tr>"
                + "</tbody></table><p class=\"positions\">p</p><span class=\"fixed no-wrap\">s</span>"
                + "<a href=\"https://example.com/x\" class=\"no-wrap\">a</a><div class=\"underline\">d</div>", BASE_URI);

        assertEquals("<table class=\"positions\"><tbody><tr class=\"underline\"><td class=\"pos-col\">1</td>"
                + "<td>2</td></tr></tbody></table><p>p</p><span class=\"no-wrap\">s</span>"
                + "<a href=\"https://example.com/x\">a</a><div>d</div>", html);
    }

    /**
     * An image on the application's own origin stays, root-relative (made absolute) or absolute, with its
     * alternative text and a width or height that is a plain CSS length. Its event handler, style, title and a
     * size that is not a plain CSS length go.
     */
    @Test
    void anImageOfTheApplicationStays() {
        String html = MailHtml.cleanMessageText(
                "<p><img src=\"/rest/repo/file/messages/a/ab/uid/chart.png\" alt=\"Chart\" width=\"200\""
                        + " height=\"10px; background: url(https://tracker.example.org/x.png)\" title=\"t\""
                        + " style=\"position:fixed\" onerror=\"alert('it-onerror')\"></p>"
                        + "<p><img src=\"" + BASE_URI + "/rest/repo/file/messages/a/ab/uid/map.png\" height=\"5cm\"></p>",
                BASE_URI);

        assertEquals("<p><img src=\"" + BASE_URI + "/rest/repo/file/messages/a/ab/uid/chart.png\" alt=\"Chart\""
                + " width=\"200\"></p>"
                + "<p><img src=\"" + BASE_URI + "/rest/repo/file/messages/a/ab/uid/map.png\" height=\"5cm\"></p>", html);

        // The scheme and host of an address are case-insensitive
        assertTrue(MailHtml.cleanMessageText(
                "<img src=\"HTTPS://NIORD.example.com/rest/repo/file/messages/a/ab/uid/plan.png\">", BASE_URI)
                .contains("/rest/repo/file/messages/a/ab/uid/plan.png\">"));
    }

    /**
     * An image from anywhere else goes: another host, a protocol-relative address, a host that only starts like
     * the application's, one that puts the application's host in the user part, another port, a {@code data:}
     * image, a script address and an image without a source.
     */
    @Test
    void aForeignImageOrADataImageIsDropped() {
        String html = MailHtml.cleanMessageText(
                "<p>1<img src=\"https://tracker.example.org/x.png\"></p>"
                        + "<p>2<img src=\"//tracker.example.org/x.png\"></p>"
                        + "<p>3<img src=\"https://niord.example.com.tracker.example.org/x.png\"></p>"
                        + "<p>4<img src=\"https://niord.example.com@tracker.example.org/x.png\"></p>"
                        + "<p>5<img src=\"https://niord.example.com:8443/x.png\"></p>"
                        + "<p>6<img src=\"http://niord.example.com/x.png\"></p>"
                        + "<p>7<img src=\"data:image/png;base64,iVBORw0KGgo=\"></p>"
                        + "<p>8<img src=\"javascript:alert(1)\"></p>"
                        + "<p>9<img alt=\"none\"></p>",
                BASE_URI);

        assertEquals("<p>1</p><p>2</p><p>3</p><p>4</p><p>5</p><p>6</p><p>7</p><p>8</p><p>9</p>", html);
    }

    @Test
    void withoutABaseUriEveryImageIsDropped() {
        assertEquals("<p>x</p>", MailHtml.cleanMessageText(
                "<p>x<img src=\"/rest/repo/file/a.png\"><img src=\"" + BASE_URI + "/rest/repo/file/b.png\"></p>", null));
        assertEquals("<p>x</p>", MailHtml.cleanMessageText("<p>x<img src=\"/rest/repo/file/a.png\"></p>", "not a uri"));
    }

    /** The base URI may carry a port and a path; its origin is what counts. */
    @Test
    void theOriginOfTheBaseUriCounts() {
        String baseUri = "http://localhost:8888/niord/";

        assertEquals("<img src=\"http://localhost:8888/rest/repo/file/a.png\">",
                MailHtml.cleanMessageText("<img src=\"/rest/repo/file/a.png\">", baseUri));
        assertEquals("", MailHtml.cleanMessageText("<img src=\"http://localhost:8889/rest/repo/file/a.png\">", baseUri));
    }

    // ------------------------------------------------------------------------------------------- //
    // The class vocabulary
    // ------------------------------------------------------------------------------------------- //

    /**
     * The class names a message text keeps are the frontends' message-HTML class vocabulary,
     * {@code MESSAGE_HTML_CLASSES} in {@code fe/shared-html/message-html-classes.ts}. Change both together.
     */
    @Test
    void theMessageClassesAreTheFrontendsVocabulary() throws IOException {
        // The frontend's list is in niord-ng only. This class also runs in every country build (the country build
        // group), from niord-base's test-jar and in a country repository's tree, where the check does not apply.
        assumeTrue(insideNiordNg(), "the frontend's list is checked in niord-ng's own build");
        String ts = Files.readString(repositoryFile(MESSAGE_HTML_CLASSES_TS));
        Matcher set = Pattern.compile("MESSAGE_HTML_CLASSES\\b[^=]*=\\s*new Set\\(\\[(.*?)]\\)", Pattern.DOTALL)
                .matcher(ts);
        assertTrue(set.find(), "no MESSAGE_HTML_CLASSES set in " + MESSAGE_HTML_CLASSES_TS);

        Set<String> frontend = new TreeSet<>();
        Matcher name = Pattern.compile("'([^']*)'|\"([^\"]*)\"").matcher(set.group(1));
        while (name.find()) {
            frontend.add(name.group(1) != null ? name.group(1) : name.group(2));
        }
        Set<String> mail = new TreeSet<>();
        MailHtml.MESSAGE_CLASSES.values().forEach(mail::addAll);

        assertFalse(frontend.isEmpty(), "no class names read from " + MESSAGE_HTML_CLASSES_TS);
        assertEquals(frontend, mail, "MailHtml.MESSAGE_CLASSES and " + MESSAGE_HTML_CLASSES_TS + " have drifted apart");
    }

    /** Whether the working directory is in a niord-ng tree: be/niord/pom.xml is at or above it */
    private static boolean insideNiordNg() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("be/niord/pom.xml"))) {
                return true;
            }
        }
        return false;
    }

    /** Finds a file of the repository from the working directory (the module's directory under Maven) upwards */
    private static Path repositoryFile(String path) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(path);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return fail(path + " not found in " + Path.of("").toAbsolutePath() + " or above it");
    }

    // ------------------------------------------------------------------------------------------- //
    // A message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everyTextOfAMessageIsCleaned() {
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("Title <script>alert('it-script')</script>");
        desc.setVicinity("Vicinity <img src=\"https://tracker.example.org/v.png\">");
        desc.setPublication("<a href=\"/rest/repo/file/publications/p.pdf\" onclick=\"alert('it-onclick')\">P</a>");
        desc.setInternalPublication("<span style=\"position:fixed\" class=\"fixed\">Internal</span>");
        desc.setSource("Source <script>alert('it-script')</script>");

        MessagePartDescVo partDesc = new MessagePartDescVo();
        partDesc.setLang("en");
        partDesc.setSubject("Subject <b onmouseover=\"alert('it-onclick')\">bold</b>");
        partDesc.setDetails(FORMATTING + MESSAGE_CLASSES + ACTIVE);
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        part.setDescs(new ArrayList<>(List.of(partDesc)));

        ReferenceDescVo refDesc = new ReferenceDescVo();
        refDesc.setLang("en");
        refDesc.setDescription("Reference <script>alert('it-script')</script>");
        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId("NW-001-26");
        ref.setDescs(new ArrayList<>(List.of(refDesc)));

        AttachmentDescVo attDesc = new AttachmentDescVo();
        attDesc.setLang("en");
        attDesc.setCaption("Caption <img src=\"data:image/png;base64,iVBORw0KGgo=\" onerror=\"alert('it-onerror')\">");
        AttachmentVo att = new AttachmentVo();
        att.setDescs(new ArrayList<>(List.of(attDesc)));

        MessageVo message = new MessageVo();
        message.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));
        message.setReferences(new ArrayList<>(List.of(ref)));
        message.setAttachments(new ArrayList<>(List.of(att)));

        assertSame(message, MailHtml.cleanMessage(message, BASE_URI));

        assertEquals("Title ", desc.getTitle());
        assertEquals("Vicinity ", desc.getVicinity());
        assertEquals("<a href=\"" + BASE_URI + "/rest/repo/file/publications/p.pdf\">P</a>", desc.getPublication());
        assertEquals("<span>Internal</span>", desc.getInternalPublication());
        assertEquals("Source ", desc.getSource());
        assertEquals("Subject <b>bold</b>", partDesc.getSubject());
        assertEquals(FORMATTING + MESSAGE_CLASSES + "<p>clicked</p>", partDesc.getDetails());
        assertNoActiveContent(partDesc.getDetails());
        assertEquals("Reference ", refDesc.getDescription());
        assertEquals("Caption ", attDesc.getCaption());
        assertEquals("NW-001-26", ref.getMessageId(), "the id of a reference is not HTML and is left alone");
    }

    private static AttachmentVo imageAttachment(String path, String width, String height) {
        AttachmentVo att = new AttachmentVo();
        att.setType("image/png");
        att.setPath(path);
        att.setWidth(width);
        att.setHeight(height);
        return att;
    }

    /**
     * The templates print an attachment's width and height into the style attribute of its image, unescaped.
     * A plain CSS length stays; anything else is dropped, and the image keeps its natural size.
     */
    @Test
    void anAttachmentWidthOrHeightThatIsNotACssLengthIsDropped() {
        AttachmentVo breaking = imageAttachment("messages/a/ab/uid/att/chart.png",
                "10px\"><a href=\"https://phish.example.org/it\">verify</a><img src=\"https://tracker.example.org/x.png",
                "5cm");
        AttachmentVo styled = imageAttachment("messages/a/ab/uid/att/map.png",
                "10px; background: url(https://tracker.example.org/y.png)", "auto");
        AttachmentVo plain = imageAttachment("messages/a/ab/uid/att/photo.png", "12.5%", " 100 PX ");
        AttachmentVo unsized = imageAttachment("messages/a/ab/uid/att/plan.png", null, "");
        MessageVo message = new MessageVo();
        message.setAttachments(new ArrayList<>(List.of(breaking, styled, plain, unsized)));

        MailHtml.cleanMessage(message, BASE_URI);

        assertEquals(List.of(breaking, styled, plain, unsized), message.getAttachments());
        assertNull(breaking.getWidth());
        assertEquals("5cm", breaking.getHeight());
        assertNull(styled.getWidth());
        assertNull(styled.getHeight());
        assertEquals("12.5%", plain.getWidth());
        assertEquals(" 100 PX ", plain.getHeight());
        assertNull(unsized.getWidth());
        assertEquals("", unsized.getHeight());
    }

    /**
     * The templates print an attachment's path into the src attribute of its image, unescaped. The repository
     * writes paths URL-encoded, so an attachment whose path holds {@code "}, {@code <}, {@code >} or {@code &}
     * was not written by it, and is left out of the mail.
     */
    @Test
    void anAttachmentWhosePathCouldLeaveItsAttributeIsLeftOut() {
        AttachmentVo encoded = imageAttachment("messages/a/ab/uid/att/Kort%20%26%20plan.png", "5cm", null);
        AttachmentVo pathless = imageAttachment(null, null, null);
        MessageVo message = new MessageVo();
        message.setAttachments(List.of(
                imageAttachment("messages/a/ab/uid/att/x.png\" onerror=\"alert('it-path')", null, null),
                encoded,
                imageAttachment("messages/a/ab/uid/att/<x>.png", null, null),
                imageAttachment("messages/a/ab/uid/att/x.png&quot;", null, null),
                pathless));

        MailHtml.cleanMessage(message, BASE_URI);

        assertEquals(List.of(encoded, pathless), message.getAttachments());
    }

    @Test
    void cleaningTwiceGivesWhatCleaningOnceGives() {
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setVicinity("Skagen Havn & <b onclick=\"x()\">Hirtshals</b> <a href=\"/rest/repo/file/p.pdf\">p</a>");
        MessagePartDescVo partDesc = new MessagePartDescVo();
        partDesc.setLang("en");
        partDesc.setDetails(MESSAGE_CLASSES + "<table class=\"fixed positions\"><tbody><tr><td>x</td></tr></tbody>"
                + "</table><img src=\"/rest/repo/file/a.png\" width=\"10px; x\" height=\"20\">"
                + "<img src=\"https://tracker.example.org/x.png\">");
        MessagePartVo part = new MessagePartVo();
        part.setDescs(new ArrayList<>(List.of(partDesc)));
        AttachmentVo att = imageAttachment("messages/a/ab/uid/att/chart.png", "10px\" onload=\"x()", "5cm");
        MessageVo message = new MessageVo();
        message.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));
        message.setAttachments(new ArrayList<>(List.of(att,
                imageAttachment("messages/a/ab/uid/att/x.png\"", null, null))));

        MailHtml.cleanMessage(message, BASE_URI);
        String vicinity = desc.getVicinity();
        String details = partDesc.getDetails();
        MailHtml.cleanMessage(message, BASE_URI);

        assertEquals(vicinity, desc.getVicinity());
        assertEquals(details, partDesc.getDetails());
        assertTrue(details.contains("<img src=\"" + BASE_URI + "/rest/repo/file/a.png\" height=\"20\">"), details);
        assertEquals(List.of(att), message.getAttachments());
        assertNull(att.getWidth());
        assertEquals("5cm", att.getHeight());
    }

    @Test
    void aMessageWithoutTextsIsLeftAsItIs() {
        MessageVo message = new MessageVo();
        message.setParts(new ArrayList<>(List.of(new MessagePartVo())));

        assertSame(message, MailHtml.cleanMessage(message, BASE_URI));
        assertNull(message.getDescs());
        assertNull(MailHtml.cleanMessage(null, BASE_URI));
    }
}
