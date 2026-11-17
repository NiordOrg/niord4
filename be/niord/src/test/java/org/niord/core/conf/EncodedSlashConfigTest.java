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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;
import org.niord.core.conf.EncodedSlashConfig.MessageIdFirewall;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The firewall of {@link EncodedSlashConfig} on its own (WEB15). A request is built as Tomcat hands it on with
 * {@code encodedSolidusHandling=passthrough}: the raw request URI, and a servlet path that keeps {@code %2F} encoded
 * and has every other escape decoded. {@code MessageShortIdWithSlashITTest} shows the same through the real
 * connector.
 */
class EncodedSlashConfigTest {

    private final MessageIdFirewall firewall = new MessageIdFirewall();

    /** A request as Tomcat passes it on: the raw URI, and the servlet path with only %2F kept encoded */
    private static MockHttpServletRequest request(String method, String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, rawUri);
        request.setServletPath(rawUri.replace("%20", " ").replace("%25", "%"));
        return request;
    }

    @Test
    void anEncodedSlashIsAcceptedOnTheThreeMessageIdReads() {
        for (String uri : new String[] {
                "/rest/public/v1/message/B%2F1%202026",
                "/rest/messages/message/B%2F1%202026",
                "/rest/message-reports/message/B%2F1%202026.pdf",
                "/rest/public/v1/message/FA%2fEK-R-18%202025",
                "/rest/public/v1/message/..%2F..%2Frest%2Fmessages%2Fsearch"}) {
            assertDoesNotThrow(() -> firewall.getFirewalledRequest(request("GET", uri)), uri);
        }
    }

    @Test
    void anEncodedSlashIsRefusedEverywhereElse() {
        for (String uri : new String[] {
                "/rest/repo/file/a%2Fb.txt",
                "/rest/areas/area%2F1",
                "/rest/public/v1/messages%2Fx",
                "/rest/public/v1/message/B%2F1%202026/x",
                "/rest/message-reports/message/B%2F1%202026",
                "/rest/message-reports/message/B%2F1%202026.pdf/x",
                "/wms/a%2Fb",
                "/api/secom/v2/a%2Fb",
                "/%2F"}) {
            assertThrows(RequestRejectedException.class,
                    () -> firewall.getFirewalledRequest(request("GET", uri)), uri);
        }
    }

    @Test
    void anEncodedSlashOnAMessageIdPathIsRefusedForAnotherMethod() {
        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}) {
            assertThrows(RequestRejectedException.class,
                    () -> firewall.getFirewalledRequest(request(method, "/rest/messages/message/B%2F1%202026")),
                    method);
        }
    }

    @Test
    void anEncodedPercentOrDoubleSlashIsRefusedOnAMessageIdPath() {
        for (String uri : new String[] {
                "/rest/public/v1/message/B%252F1%202026",
                "/rest/public/v1/message/B%25%2F1",
                "/rest/public/v1/message/B%2F%2F1",
                "/rest/public/v1/message/B%2f%2F1",
                "/rest/public/v1/message/B%2F1%3B2026",
                "/rest/public/v1/message/B%2F1%2E2026",
                "/rest/public/v1/message/B%2F1%5C2026"}) {
            assertThrows(RequestRejectedException.class,
                    () -> firewall.getFirewalledRequest(request("GET", uri)), uri);
        }
    }

    @Test
    void anOrdinaryRequestPassesAsBefore() {
        assertDoesNotThrow(() -> firewall.getFirewalledRequest(request("GET", "/rest/public/v1/message/NM-001-26")));
        assertDoesNotThrow(() -> firewall.getFirewalledRequest(request("POST", "/rest/messages/search")));
    }

    @Test
    void theMessageIdPathsAreMatchedAfterTheContextPath() {
        MockHttpServletRequest request = request("GET", "/niord/rest/public/v1/message/B%2F1%202026");
        request.setContextPath("/niord");
        request.setServletPath("/rest/public/v1/message/B%2F1 2026");
        assertTrue(MessageIdFirewall.isMessageIdRead(request));

        MockHttpServletRequest outside = request("GET", "/rest/public/v1/message/B%2F1%202026");
        outside.setContextPath("/niord");
        assertFalse(MessageIdFirewall.isMessageIdRead(outside));
    }
}
