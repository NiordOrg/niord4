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
package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the message link in a tweet composed by {@code TwitterPromulgationService.computeTweet}.
 *
 * <p>The default format links to the message through {@code ${admin-app-url}}. A format stored in
 * {@link TwitterSettings} may instead build the hash-routed link {@code ${base-uri}/#/message/${uid}};
 * the composed tweet carries the same link form either way. {@code ${base-uri}} itself keeps expanding
 * to the base URI, since a stored format may use it for other links.
 *
 * <p>No container: the service is built with a mocked {@link NiordApp}, and the tweet is composed from a
 * plain {@link Message}. Sending it is covered by {@code TwitterPromulgationHttpITTest}.
 */
public class TwitterPromulgationServiceTest {

    private static final String BASE_URI = "http://localhost:8888";
    private static final String ADMIN_APP = "https://admin.example.org";

    private static TwitterPromulgationService service(String messageLinkBaseUri) {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguages()).thenReturn(new String[]{"en"});
        when(app.getDefaultLanguage()).thenReturn("en");
        when(app.getBaseUri()).thenReturn(BASE_URI);
        when(app.getMessageLinkBaseUri()).thenReturn(messageLinkBaseUri);
        return new TwitterPromulgationService(null, null, app);
    }

    private static Message message() {
        Message message = new Message();
        message.setUid("5a8c2f9e-1d2b");
        message.setShortId("NW-015-17");
        return message;
    }

    @Test
    public void theDefaultFormatLinksToTheMessageInTheAdminApp() {
        assertEquals("NW-015-17 Buoy missing https://admin.example.org/messages?messageId=5a8c2f9e-1d2b",
                service(ADMIN_APP).computeTweet(message(), null, "Buoy missing"));
    }

    @Test
    public void theDefaultFormatLinksToTheBaseUriWhenNoAdminAppIsConfigured() {
        assertEquals("NW-015-17 Buoy missing http://localhost:8888/messages?messageId=5a8c2f9e-1d2b",
                service(BASE_URI).computeTweet(message(), "", "Buoy missing"));
    }

    @Test
    public void aStoredFormatWithAHashRoutedLinkGetsTheAdminAppLink() {
        assertEquals("NW-015-17 Buoy missing https://admin.example.org/messages?messageId=5a8c2f9e-1d2b",
                service(ADMIN_APP).computeTweet(message(), "${short-id} ${tweet} ${base-uri}/#/message/${uid}",
                        "Buoy missing"));
    }

    @Test
    public void baseUriStillExpandsToTheBaseUri() {
        assertEquals("Buoy missing http://localhost:8888/rest/message-map-image/5a8c2f9e-1d2b.png",
                service(ADMIN_APP).computeTweet(message(), "${tweet} ${base-uri}/rest/message-map-image/${uid}.png",
                        "Buoy missing"));
    }
}
