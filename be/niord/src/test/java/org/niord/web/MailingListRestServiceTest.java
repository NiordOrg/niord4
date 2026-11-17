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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.MailingListExecutionService;
import org.niord.core.mailinglist.MailingListService;
import org.niord.core.message.MessageService;
import org.niord.core.user.ContactService;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.core.web.NiordWebException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MailingListRestService#executeMailingListReport}: the report prints message texts that editors
 * wrote, markup included, and the administration application opens it in a window of its own on the
 * backend origin, so the HTML is served sandboxed.
 */
class MailingListRestServiceTest {

    private UserService userService;
    private MailingListExecutionService executionService;
    private MailingListRestService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        executionService = mock(MailingListExecutionService.class);
        service = new MailingListRestService(mock(MailingListService.class), executionService, userService,
                mock(ContactService.class), mock(MessageService.class));
    }

    @Test
    void aUserGetsTheReportSandboxedAsHtml() throws Exception {
        when(userService.isCallerInRole(Roles.USER)).thenReturn(true);
        String html = "<html><body><p>Details <img src=x onerror=alert(1)></p></body></html>";
        when(executionService.executeMailingListReport(7, "da")).thenReturn(html);

        ResponseEntity<String> response = service.executeMailingListReport(7, "da");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(html, response.getBody());
        assertEquals("text/html;charset=UTF-8", String.valueOf(response.getHeaders().getContentType()));
        // An origin of its own, no script, no forms: markup in a message text stays something to look at
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    void aCallerWithoutTheUserRoleIsForbiddenAndNothingIsRendered() throws Exception {
        when(userService.isCallerInRole(Roles.USER)).thenReturn(false);

        NiordWebException e = assertThrows(NiordWebException.class,
                () -> service.executeMailingListReport(7, "da"));

        assertEquals(403, e.getStatusCode().value());
        verify(executionService, never()).executeMailingListReport(anyInt(), any());
    }
}
