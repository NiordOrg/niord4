/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.mailinglist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the observable behavior of {@link MailingListSearchParams}, a plain (framework-neutral)
 * search-criteria holder.
 *
 * <p>Behavior pinned:
 * <ul>
 *   <li>The exact {@code toString()} rendering, which is human-facing search-criteria
 *       text. Each present field is formatted with a specific label and joined with
 *       {@code ", "} (comma + single space). The non-obvious oracle is the quoting:
 *       {@code name}, {@code mailingListId}, {@code username} and {@code contactEmail}
 *       are wrapped in single quotes, while {@code language} is NOT quoted.</li>
 *   <li>Field inclusion is governed by {@code StringUtils.isNotBlank}, so {@code null},
 *       empty, and whitespace-only values are all treated as absent and omitted entirely
 *       (no label and no separator emitted for them).</li>
 *   <li>The fluent setters return the same instance (enabling method chaining) and the
 *       corresponding getters reflect the stored value.</li>
 * </ul>
 *
 * <p>These tests use no Quarkus, persistence, or framework features and should survive a
 * future Quarkus-to-Spring port unchanged.
 */
public class MailingListSearchParamsTest {

    @Test
    public void toStringRendersAllFiveFieldsInOrderWithExactQuotingAndCommaSpaceJoining() {
        MailingListSearchParams params = new MailingListSearchParams()
                .name("N")
                .language("en")
                .mailingListId("ID")
                .username("U")
                .contactEmail("E");

        // language is intentionally NOT single-quoted; the other four are.
        assertEquals(
                "Name: 'N', Language: en, Mailing-list ID: 'ID', Username: 'U', Contact email: 'E'",
                params.toString());
    }

    @Test
    public void toStringIsEmptyStringWhenNoFieldsAreSet() {
        MailingListSearchParams params = new MailingListSearchParams();

        // Nothing present -> the joined description is the empty string, not "null" or "[]".
        assertEquals("", params.toString());
    }

    @Test
    public void toStringRendersOnlyTheNameWhenOnlyNameIsSet() {
        MailingListSearchParams params = new MailingListSearchParams().name("N");

        // No leading/trailing separators are emitted for absent fields.
        assertEquals("Name: 'N'", params.toString());
    }

    @Test
    public void toStringTreatsWhitespaceOnlyValuesAsBlankAndOmitsThem() {
        // isNotBlank() rejects whitespace-only strings, so none of these contribute output.
        MailingListSearchParams params = new MailingListSearchParams()
                .name("   ")
                .language("\t")
                .mailingListId(" ")
                .username("")
                .contactEmail(null);

        assertEquals("", params.toString());
    }

    @Test
    public void toStringSkipsBlankFieldsButStillRendersPresentOnesWithoutDanglingSeparators() {
        // A real field surrounded by blank fields must not produce stray ", " separators.
        MailingListSearchParams params = new MailingListSearchParams()
                .name("   ")          // blank -> omitted
                .language("da")       // present -> rendered, unquoted
                .mailingListId(null)  // null -> omitted
                .username("alice")    // present -> rendered, single-quoted
                .contactEmail("");    // empty -> omitted

        assertEquals("Language: da, Username: 'alice'", params.toString());
    }

    @Test
    public void fluentSettersReturnSameInstanceAndGettersReflectStoredValues() {
        MailingListSearchParams params = new MailingListSearchParams();

        assertSame(params, params.name("N"), "name() must return the same instance for chaining");
        assertSame(params, params.language("en"), "language() must return the same instance for chaining");
        assertSame(params, params.mailingListId("ID"), "mailingListId() must return the same instance for chaining");
        assertSame(params, params.username("U"), "username() must return the same instance for chaining");
        assertSame(params, params.contactEmail("E"), "contactEmail() must return the same instance for chaining");

        assertEquals("N", params.getName());
        assertEquals("en", params.getLanguage());
        assertEquals("ID", params.getMailingListId());
        assertEquals("U", params.getUsername());
        assertEquals("E", params.getContactEmail());
    }
}
