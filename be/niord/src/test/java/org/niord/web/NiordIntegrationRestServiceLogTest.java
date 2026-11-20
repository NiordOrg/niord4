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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.core.integration.NiordIntegrationService;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.niord.test.JmsFaults;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT8. Creating an integration whose address carries a credential is refused by the service, and the controller,
 * which logs before the service checks the address, does not write the address to the log: it logged it whole.
 */
class NiordIntegrationRestServiceLogTest {

    @Test
    void aRefusedAddressWithACredential_isNotLogged() {
        NiordIntegrationService service = mock(NiordIntegrationService.class);
        when(service.createNiordIntegration(any()))
                .thenThrow(new IllegalArgumentException("Niord integration URL must not contain user info"));
        NiordIntegrationRestService controller = new NiordIntegrationRestService(service);
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setUrl("https://user:secret@peer.example.org");

        ListAppender<ILoggingEvent> log = JmsFaults.captureLog(NiordIntegrationRestService.class);
        try {
            assertThrows(IllegalArgumentException.class, () -> controller.createNiordIntegration(vo));
        } finally {
            JmsFaults.stopCapture(NiordIntegrationRestService.class, log);
        }

        List<String> lines = log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(l -> l.contains("Creating Niord integration point")), lines.toString());
        assertFalse(lines.stream().anyMatch(l -> l.contains("secret") || l.contains("peer.example.org")),
                "the address is not logged before it is checked: " + lines);
    }
}
