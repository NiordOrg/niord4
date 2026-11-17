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
package org.niord.web;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The e-mail suggestions of {@code GET /rest/users/search-emails} are what the comment dialog sends back as recipients.
 * The comment endpoint refuses an entry that parses into more than one mailbox, so every suggestion must parse back as
 * exactly one — also for a name with a comma — and a suggestion for an ordinary name must look as it always did.
 */
class UserEmailSuggestionTest {

    @Test
    void anOrdinaryNameIsUnchanged() {
        assertEquals("John Doe <john@example.dk>", UserRestService.emailSuggestion("John Doe", "john@example.dk"));
    }

    @Test
    void danishLettersStayReadableAndParseAsOneMailbox() throws Exception {
        String suggestion = UserRestService.emailSuggestion("Jørgen Ærø Åberg", "jorgen@example.dk");

        assertEquals("Jørgen Ærø Åberg <jorgen@example.dk>", suggestion);
        assertOneMailbox(suggestion, "jorgen@example.dk");
    }

    @Test
    void aNameWithACommaIsQuotedAndParsesAsOneMailbox() throws Exception {
        String suggestion = UserRestService.emailSuggestion("Doe, John", "john@example.dk");

        assertEquals("\"Doe, John\" <john@example.dk>", suggestion);
        assertOneMailbox(suggestion, "john@example.dk");
    }

    @Test
    void aNameWithGroupSyntaxCharactersIsQuotedAndParsesAsOneMailbox() throws Exception {
        assertOneMailbox(UserRestService.emailSuggestion("Team: Ops;", "ops@example.dk"), "ops@example.dk");
    }

    @Test
    void aNameWithAnInitialOrAQuoteIsQuotedAndParsesAsOneMailbox() throws Exception {
        assertEquals("\"J. Doe\" <j@example.dk>", UserRestService.emailSuggestion("J. Doe", "j@example.dk"));
        assertOneMailbox(UserRestService.emailSuggestion("John \"Jack\" Doe", "jack@example.dk"), "jack@example.dk");
    }

    @Test
    void aMissingNameGivesTheBareAddress() {
        assertEquals("john@example.dk", UserRestService.emailSuggestion(null, "john@example.dk"));
    }

    @Test
    void aUserWithoutAnAddressGivesNoSuggestion() {
        // A deleted user's record keeps the name but not the address (USER1); before, this gave "John Doe <null>"
        assertNull(UserRestService.emailSuggestion("John Doe", null));
        assertNull(UserRestService.emailSuggestion("John Doe", " "));
    }

    /** Parses the suggestion the way the comment endpoint does (strictly) and expects one non-group mailbox. */
    private static void assertOneMailbox(String suggestion, String expectedAddress) throws Exception {
        List<InternetAddress> parsed = List.of(InternetAddress.parse(suggestion, true));
        assertEquals(1, parsed.size(), "one mailbox: " + suggestion);
        assertEquals(false, parsed.getFirst().isGroup(), "not a group: " + suggestion);
        assertEquals(expectedAddress, parsed.getFirst().getAddress());
    }
}
