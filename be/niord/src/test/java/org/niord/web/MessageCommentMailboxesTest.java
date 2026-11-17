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
import org.niord.core.web.NiordWebException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.web.MessageCommentRestService.MAX_COMMENT_MAIL_RECIPIENTS;
import static org.niord.web.MessageCommentRestService.commentMailboxes;

/**
 * {@link MessageCommentRestService#commentMailboxes}: a comment is mailed to at most
 * {@link MessageCommentRestService#MAX_COMMENT_MAIL_RECIPIENTS} mailboxes, one mail each with the full message
 * attached. The cap counts mailboxes, not the strings of the list, so an RFC 822 group address - one string
 * the mail server expands to every mailbox in it - is refused, as is an entry of more than one address.
 */
class MessageCommentMailboxesTest {

    /** Returns {@code count} plain addresses. */
    private static List<String> addresses(int count) {
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            addresses.add("it-" + i + "@example.com");
        }
        return addresses;
    }

    /** Returns a group address of the given name that holds {@code count} mailboxes. */
    private static String group(String name, int count) {
        return name + ": " + String.join(", ", addresses(count)) + ";";
    }

    /** Returns the bare e-mail addresses of the mailboxes. */
    private static List<String> bare(List<InternetAddress> mailboxes) {
        return mailboxes.stream().map(InternetAddress::getAddress).toList();
    }

    private static void assertRefused(List<String> emailAddresses) {
        NiordWebException e = assertThrows(NiordWebException.class, () -> commentMailboxes(emailAddresses));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void noAddressesMeanNoMailboxes() {
        assertTrue(commentMailboxes(null).isEmpty());
        assertTrue(commentMailboxes(List.of()).isEmpty());
    }

    @Test
    void eachPlainAddressIsOneMailbox() {
        List<InternetAddress> mailboxes = commentMailboxes(List.of(
                "a@example.com", "Rasmus Jensen <r@example.com>", "\"Doe, John\" <j@example.com>"));

        assertEquals(List.of("a@example.com", "r@example.com", "j@example.com"), bare(mailboxes));
        assertEquals("Rasmus Jensen", mailboxes.get(1).getPersonal());
        mailboxes.forEach(m -> assertFalse(m.isGroup()));
    }

    @Test
    void theCapOfPlainAddressesIsAccepted() {
        List<String> addresses = addresses(MAX_COMMENT_MAIL_RECIPIENTS);
        assertEquals(addresses, bare(commentMailboxes(addresses)));
    }

    @Test
    void oneAddressMoreThanTheCapIsRefused() {
        assertRefused(addresses(MAX_COMMENT_MAIL_RECIPIENTS + 1));
    }

    @Test
    void aGroupOfMoreMailboxesThanTheCapIsRefused() {
        // One string that passes InternetAddress.validate() and is sent to all 21 mailboxes in it.
        String group = group("IT", MAX_COMMENT_MAIL_RECIPIENTS + 1);
        assertRefused(List.of(group));
        assertRefused(List.of("a@example.com", group));
    }

    @Test
    void aGroupIsRefusedWhateverItsSize() {
        assertRefused(List.of(group("Team", 2)));
        assertRefused(List.of(group("Team", 1)));
        assertRefused(List.of("undisclosed-recipients:;"));
    }

    @Test
    void anEntryOfMoreThanOneAddressIsRefused() {
        assertRefused(List.of("a@example.com, b@example.com"));
        // A group followed by a mailbox, as a user name ending in a group would give "Name <address>"
        assertRefused(List.of("IT: a@example.com, b@example.com; <c@example.com>"));
    }

    @Test
    void anEntryThatIsNotAnAddressIsLeftOutAsBefore() {
        List<String> addresses = Arrays.asList("a@example.com", "not an address", "", null, "b@example.com");
        assertEquals(List.of("a@example.com", "b@example.com"), bare(commentMailboxes(addresses)));
    }
}
