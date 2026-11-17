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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.niord.core.user.Contact;
import org.niord.core.user.ContactService;
import org.niord.core.user.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The count {@code POST /rest/contacts/import-emails} answers (CONTACT2): it counts the contacts created, so an
 * address whose write fails is not counted, and an address given more than once, in any case, is tried once.
 */
class ContactRestServiceImportTest {

    private final ContactService contactService = mock(ContactService.class);
    private final UserService userService = mock(UserService.class);
    private final ContactRestService rest = new ContactRestService(contactService, userService);

    private String importEmails(String emails) throws Exception {
        ContactRestService.EmailContactImportVo param = new ContactRestService.EmailContactImportVo();
        param.setEmails(emails);
        return rest.importEmails(param);
    }

    @Test
    void anAddressWhoseWriteFailsIsNotCounted() throws Exception {
        when(contactService.importContact(any())).thenAnswer(inv -> {
            Contact contact = inv.getArgument(0);
            if (contact.getEmail().startsWith("fails")) {
                throw new IllegalStateException("the write failed");
            }
            return contact;
        });

        String answer = importEmails("ok-1@example.org, fails@example.org; ok-2@example.org");

        assertTrue(answer.startsWith("Imported 2 new contacts in "), answer);
        verify(contactService, times(3)).importContact(any());
    }

    @Test
    void anAddressGivenTwiceInAnyCaseIsTriedOnceInItsFirstSpelling() throws Exception {
        when(contactService.importContact(any())).thenAnswer(inv -> inv.getArgument(0));

        String answer = importEmails("First@Example.org\nfirst@example.org;FIRST@EXAMPLE.ORG , other@example.org,"
                + " not-an-address, ,");

        assertTrue(answer.startsWith("Imported 2 new contacts in "), answer);
        ArgumentCaptor<Contact> created = ArgumentCaptor.forClass(Contact.class);
        verify(contactService, times(2)).importContact(created.capture());
        assertEquals(List.of("First@Example.org", "other@example.org"),
                created.getAllValues().stream().map(Contact::getEmail).toList());
    }

    @Test
    void anAddressThatIsAlreadyAContactOrAUserIsSkippedAndNotCounted() throws Exception {
        when(contactService.findByEmail("contact@example.org")).thenReturn(new Contact());
        when(userService.findByEmail("user@example.org")).thenReturn(new org.niord.core.user.User());
        when(contactService.importContact(any())).thenAnswer(inv -> inv.getArgument(0));

        String answer = importEmails("contact@example.org, user@example.org, new@example.org");

        assertTrue(answer.startsWith("Imported 1 new contacts in "), answer);
        verify(contactService, times(1)).importContact(any());
    }
}
