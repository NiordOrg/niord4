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
package org.niord.core.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line {@link FmTemplateService#withTopNotice(String, String)} puts at the top of a document, whichever template
 * made it: right after the opening body tag, escaped, and nothing at all for a blank text.
 */
class FmTemplateTopNoticeTest {

    @Test
    void theLineFollowsTheOpeningBodyTag() {
        String html = "<html><head><title>t</title></head>\n<BODY class=\"x\">\n<h1>List</h1></body></html>";

        String result = FmTemplateService.withTopNotice(html, "Messages may be missing.");

        assertTrue(result.startsWith("<html><head><title>t</title></head>\n<BODY class=\"x\"><p class=\"top-notice\""),
                result);
        assertTrue(result.contains(">Messages may be missing.</p>\n<h1>List</h1>"), result);
    }

    @Test
    void aDocumentWithoutABodyTagGetsTheLineFirst() {
        String result = FmTemplateService.withTopNotice("<div>Report</div>", "Messages may be missing.");

        assertTrue(result.startsWith("<p class=\"top-notice\""), result);
        assertTrue(result.endsWith("Messages may be missing.</p><div>Report</div>"), result);
    }

    @Test
    void theTextIsEscaped() {
        String result = FmTemplateService.withTopNotice("<body></body>", "<b>A & B</b>");

        assertTrue(result.contains(">&lt;b&gt;A &amp; B&lt;/b&gt;</p>"), result);
    }

    @Test
    void noTextLeavesTheDocumentAsItIs() {
        String html = "<body><h1>List</h1></body>";

        assertSame(html, FmTemplateService.withTopNotice(html, null));
        assertSame(html, FmTemplateService.withTopNotice(html, " "));
        assertEquals(null, FmTemplateService.withTopNotice(null, "text"));
    }
}
