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

package org.niord.web.publication;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.IssueLifecycleService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The request-body readers of {@link PublicationIssueRestService}: a reason sent as JSON null reads as no reason
 * (ISSUE3), and a malformed {@code acknowledgedWarnings} is a coded refusal, not a 500 (ISSUE4).
 */
class IssueRequestBodyTest {

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put(key, value);
        return body;
    }

    @Test
    void aNullAnAbsentAndABlankTextAllReadAsEmpty() {
        assertEquals("", PublicationIssueRestService.textOf(null, "reason"), "no body");
        assertEquals("", PublicationIssueRestService.textOf(Map.of(), "reason"), "the key absent");
        assertEquals("", PublicationIssueRestService.textOf(body("reason", null), "reason"),
                "an explicit null is not the text \"null\"");
        assertEquals("", PublicationIssueRestService.textOf(body("reason", "   "), "reason"), "blank");
    }

    @Test
    void aTextIsTrimmed() {
        assertEquals("a real reason", PublicationIssueRestService.textOf(body("reason", "  a real reason \n"), "reason"));
        assertEquals("null", PublicationIssueRestService.textOf(body("reason", "null"), "reason"),
                "the word typed by a person is kept");
    }

    @Test
    void aNullOrAbsentAcknowledgementIsTheEmptySet() {
        assertEquals(Set.of(), PublicationIssueRestService.acknowledgedOf(null), "no body");
        assertEquals(Set.of(), PublicationIssueRestService.acknowledgedOf(Map.of()), "the key absent");
        assertEquals(Set.of(), PublicationIssueRestService.acknowledgedOf(body("acknowledgedWarnings", null)),
                "an explicit null");
        assertEquals(Set.of(), PublicationIssueRestService.acknowledgedOf(body("acknowledgedWarnings", List.of())),
                "an empty list");
    }

    @Test
    void aListOfCodesIsTheCodes() {
        assertEquals(Set.of("A", "B"),
                PublicationIssueRestService.acknowledgedOf(body("acknowledgedWarnings", List.of("A", "B"))));
        assertEquals(Set.of("A"),
                PublicationIssueRestService.acknowledgedOf(body("acknowledgedWarnings", List.of("A", "A"))),
                "a code sent twice is one code");
    }

    @Test
    void aValueThatIsNotAListOfCodesIsRefusedWithACode() {
        for (Object bad : List.of("A", Map.of(), Map.of("A", true), Arrays.asList((Object) null), List.of(1),
                List.of("A", ""), List.of(" "), 7, true)) {
            IssueLifecycleService.TransitionRefusedException e = assertThrows(
                    IssueLifecycleService.TransitionRefusedException.class,
                    () -> PublicationIssueRestService.acknowledgedOf(body("acknowledgedWarnings", bad)),
                    "refused: " + bad);
            assertEquals("INVALID_FILTER_VALUE", e.code(), "the code for " + bad);
        }
    }
}
