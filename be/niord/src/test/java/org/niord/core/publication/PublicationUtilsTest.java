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
package org.niord.core.publication;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.MessagePublicationVo;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.model.message.MessageVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PublicationUtils} — the pure HTML/text logic that links publications to
 * messages: {@code computeMessagePublication}, {@code extractMessagePublication} and
 * {@code updateMessagePublications}.
 *
 * <p>These are framework-neutral plain JUnit 4 tests (no Quarkus, no DB, no mocks). They assert
 * concrete computed HTML, extracted parameters/links and thrown/returned nulls so they survive a
 * future Spring port. Every test builds its own value objects and is order-independent.
 *
 * <p>The pre-existing {@code org.niord.core.PublicationTest} only smoke-tests these methods with
 * {@code assertNotNull} and {@code System.out.println}; this class adds the meaningful-outcome
 * assertions the task requires (exact HTML, extracted parameters, internal-vs-external framing,
 * link overrides, append-vs-replace behaviour and the null sanity-check guards).
 */
public class PublicationUtilsTest {

    /** Builds a SystemPublicationVo with a single-language messagePublicationFormat/link. */
    private SystemPublicationVo publication(String id, MessagePublication type, String lang,
                                            String format, String link) {
        SystemPublicationVo pub = new SystemPublicationVo();
        pub.setPublicationId(id);
        pub.setMessagePublication(type);
        if (format != null) {
            pub.checkCreateDesc(lang).setMessagePublicationFormat(format);
        }
        if (link != null) {
            pub.checkCreateDesc(lang).setLink(link);
        }
        return pub;
    }

    // --------------------------------------------------------------------------------------------- //
    // computeMessagePublication
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void computeExternalWithLinkRendersAnchorWithTrailingDot() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "Doc ${parameters}", "http://example.dk/doc");

        String html = PublicationUtils.computeMessagePublication(pub, "5", null, "en");

        // ${parameters} substituted, a trailing dot added, wrapped in an <a> because a link exists.
        assertEquals("<a publication=\"P1\" href=\"http://example.dk/doc\" target=\"_blank\">Doc 5.</a>", html);
    }

    @Test
    public void computeExternalWithoutLinkRendersSpan() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "J.no ${parameters}", null);

        String html = PublicationUtils.computeMessagePublication(pub, "123", null, "en");

        // No link anywhere -> a <span> is produced instead of an <a>.
        assertEquals("<span publication=\"P1\">J.no 123.</span>", html);
    }

    @Test
    public void computeInternalWrapsTextInBrackets() {
        SystemPublicationVo pub = publication("P1", MessagePublication.INTERNAL, "en",
                "Note ${parameters}", null);

        String html = PublicationUtils.computeMessagePublication(pub, "9", null, "en");

        // Internal publications are bracketed before the trailing dot is appended.
        assertEquals("<span publication=\"P1\">[Note 9].</span>", html);
    }

    @Test
    public void computeUsesExplicitLinkOverPublicationDescLink() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "Doc ${parameters}", "http://default.dk");

        String html = PublicationUtils.computeMessagePublication(pub, "1", "http://override.dk", "en");

        // The explicit link argument wins over the publication-desc link.
        assertEquals("<a publication=\"P1\" href=\"http://override.dk\" target=\"_blank\">Doc 1.</a>", html);
    }

    @Test
    public void computeWithNullParametersSubstitutesEmptyString() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "Prefix ${parameters} suffix", null);

        String html = PublicationUtils.computeMessagePublication(pub, null, null, "en");

        // null parameters -> "" so the placeholder collapses; note the leftover double space is kept.
        assertEquals("<span publication=\"P1\">Prefix  suffix.</span>", html);
    }

    @Test
    public void computeReturnsNullWhenFormatBlank() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en", "   ", null);
        assertNull(PublicationUtils.computeMessagePublication(pub, "x", null, "en"));
    }

    @Test
    public void computeReturnsNullWhenDescMissingForLanguage() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "Doc ${parameters}", null);
        // No Danish description exists -> nothing to format.
        assertNull(PublicationUtils.computeMessagePublication(pub, "x", null, "da"));
    }

    // --------------------------------------------------------------------------------------------- //
    // extractMessagePublication
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void extractReturnsPublicationWithoutLinkWhenHrefMatchesDescLink() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication(
                "<a publication=\"P1\" href=\"http://link.dk\" target=\"_blank\">www.example.dk.</a>");

        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "www.example.dk", "http://link.dk");

        MessagePublicationVo result = PublicationUtils.extractMessagePublication(msg, pub, "en");

        assertEquals("P1", result.getPublication().getPublicationId());
        // The anchor href equals the publication-desc link, so no per-message link override is recorded.
        assertNull(result.getLink());
        // The format has no ${parameters}, so no parameters are extracted.
        assertNull(result.getParameters());
    }

    @Test
    public void extractParsesParametersFromFormat() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication(
                "<a publication=\"P2\" href=\"http://x.dk\" target=\"_blank\">Section 17, 18.</a>");

        SystemPublicationVo pub = publication("P2", MessagePublication.EXTERNAL, "en",
                "Section ${parameters}", "http://x.dk");

        MessagePublicationVo result = PublicationUtils.extractMessagePublication(msg, pub, "en");

        // "Section 17, 18." minus trailing dot, minus the "Section " prefix -> "17, 18".
        assertEquals("17, 18", result.getParameters());
        assertNull(result.getLink());
    }

    @Test
    public void extractRecordsLinkWhenHrefDiffersFromDescLink() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication(
                "<a publication=\"P3\" href=\"http://different.dk\" target=\"_blank\">Text.</a>");

        SystemPublicationVo pub = publication("P3", MessagePublication.EXTERNAL, "en",
                "Text", "http://original.dk");

        MessagePublicationVo result = PublicationUtils.extractMessagePublication(msg, pub, "en");

        // The message anchor points somewhere other than the desc link, so it is captured.
        assertEquals("http://different.dk", result.getLink());
    }

    @Test
    public void extractFromInternalPublicationStripsBrackets() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setInternalPublication(
                "<span publication=\"P4\">[Note 5]</span>");

        SystemPublicationVo pub = publication("P4", MessagePublication.INTERNAL, "en",
                "Note ${parameters}", null);

        MessagePublicationVo result = PublicationUtils.extractMessagePublication(msg, pub, "en");

        // Internal text is bracketed; the brackets are stripped before the prefix is matched.
        assertEquals("5", result.getParameters());
        assertNull(result.getLink());
    }

    @Test
    public void extractReturnsNullWhenNoMatchingPublicationElement() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication(
                "<a publication=\"OTHER\" href=\"http://x.dk\" target=\"_blank\">Text.</a>");

        SystemPublicationVo pub = publication("P5", MessagePublication.EXTERNAL, "en", "Text", "http://x.dk");

        assertNull(PublicationUtils.extractMessagePublication(msg, pub, "en"));
    }

    @Test
    public void extractReturnsNullForNullMessageOrPublication() {
        SystemPublicationVo pub = publication("P6", MessagePublication.EXTERNAL, "en", "Text", null);
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("<span publication=\"P6\">Text</span>");

        assertNull(PublicationUtils.extractMessagePublication(null, pub, "en"));
        assertNull(PublicationUtils.extractMessagePublication(msg, null, "en"));
    }

    @Test
    public void extractReturnsNullWhenPublicationHtmlBlank() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("   ");

        SystemPublicationVo pub = publication("P7", MessagePublication.EXTERNAL, "en", "Text", null);

        assertNull(PublicationUtils.extractMessagePublication(msg, pub, "en"));
    }

    // --------------------------------------------------------------------------------------------- //
    // updateMessagePublications
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateAppendsPublicationWhenNonePresent() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("");

        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "J.no ${parameters}", null);

        PublicationUtils.updateMessagePublications(msg, pub, "123", null, "en");

        // The append path uses the literal String.format output (no Jsoup re-serialisation), trimmed.
        assertEquals("<span publication=\"P1\">J.no 123.</span>",
                msg.getDesc("en").getPublication());
    }

    @Test
    public void updateReplacesExistingPublicationElement() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication(
                "<a publication=\"P1\" href=\"http://old.dk\" target=\"_blank\">Old text.</a>");

        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en",
                "New ${parameters}", "http://new.dk");

        PublicationUtils.updateMessagePublications(msg, pub, "9", null, "en");

        String updated = msg.getDesc("en").getPublication();
        // The existing anchor for P1 is replaced in place: new href + text, old href + text gone.
        assertTrue(updated.contains("http://new.dk"), "expected new href, was: " + updated);
        assertTrue(updated.contains("New 9."), "expected new text, was: " + updated);
        assertFalse(updated.contains("http://old.dk"), "old href must be gone, was: " + updated);
        assertFalse(updated.contains("Old text"), "old text must be gone, was: " + updated);
        assertTrue(updated.contains("publication=\"P1\""));
    }

    @Test
    public void updateOnlyTouchesRequestedLanguage() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("");
        msg.checkCreateDesc("da").setPublication("");

        SystemPublicationVo pub = new SystemPublicationVo();
        pub.setPublicationId("P1");
        pub.setMessagePublication(MessagePublication.EXTERNAL);
        pub.checkCreateDesc("en").setMessagePublicationFormat("EN ${parameters}");
        pub.checkCreateDesc("da").setMessagePublicationFormat("DA ${parameters}");

        // lang == "en" -> only the English description is modified.
        PublicationUtils.updateMessagePublications(msg, pub, "1", null, "en");

        assertEquals("<span publication=\"P1\">EN 1.</span>", msg.getDesc("en").getPublication());
        assertEquals("", msg.getDesc("da").getPublication());
    }

    @Test
    public void updateAllLanguagesWhenLangNull() {
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("");
        msg.checkCreateDesc("da").setPublication("");

        SystemPublicationVo pub = new SystemPublicationVo();
        pub.setPublicationId("P1");
        pub.setMessagePublication(MessagePublication.EXTERNAL);
        pub.checkCreateDesc("en").setMessagePublicationFormat("EN ${parameters}");
        pub.checkCreateDesc("da").setMessagePublicationFormat("DA ${parameters}");

        // lang == null -> every description is updated.
        PublicationUtils.updateMessagePublications(msg, pub, "1", null, null);

        assertEquals("<span publication=\"P1\">EN 1.</span>", msg.getDesc("en").getPublication());
        assertEquals("<span publication=\"P1\">DA 1.</span>", msg.getDesc("da").getPublication());
    }

    @Test
    public void updateReturnsNullForNullMessageOrPublication() {
        SystemPublicationVo pub = publication("P1", MessagePublication.EXTERNAL, "en", "Text", null);
        MessageVo msg = new MessageVo();
        msg.checkCreateDesc("en").setPublication("");

        assertNull(PublicationUtils.updateMessagePublications(null, pub, "x", null, null));
        assertNull(PublicationUtils.updateMessagePublications(msg, null, "x", null, null));
    }
}
