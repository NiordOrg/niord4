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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The address check of the message mail (MAIL8): every address is read before any mail is made, and a missing,
 * blank or malformed one is refused with 400 and a detail that names it. The endpoints themselves are
 * {@link MessageMailRestServiceITTest}'s.
 */
class MessageMailRestServiceTest {

    private static NiordWebException refusal(List<String> addresses) {
        return assertThrows(NiordWebException.class, () -> MessageMailRestService.recipients(addresses));
    }

    @Test
    void mailAddressesAreReadInTheirOrder_withOrWithoutAName() {
        List<InternetAddress> recipients = MessageMailRestService.recipients(
                List.of("first@example.com", "Second Reader <second@example.org>"));
        assertEquals(List.of("first@example.com", "second@example.org"),
                recipients.stream().map(InternetAddress::getAddress).toList());
        assertEquals("Second Reader", recipients.get(1).getPersonal());
    }

    @Test
    void noAddressIsRefused() {
        for (List<String> none : Arrays.asList(null, List.<String>of())) {
            NiordWebException e = refusal(none);
            assertEquals(400, e.getStatusCode().value());
            assertEquals("A message mail needs at least one address", e.getReason());
        }
    }

    @Test
    void aBlankAddressIsRefused_wherever_itStands() {
        for (String blank : Arrays.asList(null, "", " ", "\t")) {
            NiordWebException e = refusal(Arrays.asList("good@example.com", blank));
            assertEquals(400, e.getStatusCode().value());
            assertEquals("A message mail cannot be sent to a blank address", e.getReason());
        }
    }

    @Test
    void aMalformedAddressIsRefused_andNamed() {
        for (String bad : List.of("no-domain", "two@ats@example.com", "a@example.com, b@example.com",
                "team: a@example.com;", "<unclosed@example.com", "spaces in@example.com", "@example.com")) {
            NiordWebException e = refusal(List.of("good@example.com", bad));
            assertEquals(400, e.getStatusCode().value(), bad);
            assertEquals("'" + bad + "' is not a mail address", e.getReason(), bad);
        }
    }
}
