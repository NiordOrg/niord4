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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A publication's link is an http or https address or a repository path; anything else is refused with
 * LINK_INVALID (ISSUE8).
 */
class IssueLinkRuleTest {

    @Test
    void anHttpOrHttpsAddressOrARepositoryPathIsAcceptedTrimmed() {
        assertEquals("https://example.org/a.pdf", IssueLinkRule.validated("https://example.org/a.pdf"));
        assertEquals("HTTP://x.dk/a", IssueLinkRule.validated("HTTP://x.dk/a"), "the scheme in any case");
        assertEquals("/rest/repo/file/publications/x.pdf", IssueLinkRule.validated("/rest/repo/file/publications/x.pdf"));
        assertEquals("https://example.org/a%20b.pdf", IssueLinkRule.validated("  https://example.org/a%20b.pdf  "),
                "trimmed");
        assertEquals("/rest/repo/file/publications/a/8e/1/nm week 36.pdf",
                IssueLinkRule.validated("/rest/repo/file/publications/a/8e/1/nm week 36.pdf"),
                "a hosted file's name may hold a space, as its derived address does");
        assertEquals("/rest/repo/file/x/nm%20week%2036.pdf?download=1",
                IssueLinkRule.validated("/rest/repo/file/x/nm%20week%2036.pdf?download=1"));
    }

    @Test
    void aBlankLinkIsNull() {
        assertNull(IssueLinkRule.validated(null));
        assertNull(IssueLinkRule.validated(""));
        assertNull(IssueLinkRule.validated("  "));
    }

    @Test
    void anythingElseIsRefusedWithLinkInvalid() {
        for (String link : List.of("javascript:alert(1)", "JavaScript:alert(1)", "not a url at all",
                "https://x/\"onmouseover=1", "ftp://x/a", "//x/a", "https:///a.pdf", "mailto:a@example.org",
                "data:text/html,x", "/rest/repo/", "/rest/repo/file/a\".pdf", "/rest/repo/file/<b>.pdf",
                "/rest/repo/file/a\n.pdf", "/other/x.pdf", "rest/repo/file/x.pdf", "https://exa mple.org/a")) {
            IssueLifecycleService.TransitionRefusedException e = assertThrows(
                    IssueLifecycleService.TransitionRefusedException.class, () -> IssueLinkRule.validated(link),
                    "refused: " + link);
            assertEquals("LINK_INVALID", e.code(), "the code for " + link);
            assertTrue(e.getMessage().contains("http or https"), "the message says what is wanted: " + e.getMessage());
        }
    }
}
