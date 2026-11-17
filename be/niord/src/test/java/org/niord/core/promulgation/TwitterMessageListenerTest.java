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
package org.niord.core.promulgation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.jms.FakeMapMessage;
import org.niord.core.jms.UnsupportedJmsMessage;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the consume side of the message-status topic flow handled by
 * {@link TwitterMessageListener#onMessage(jakarta.jms.Message)}.
 *
 * <p>These pin the routing contract the listener applies to a status-update {@link jakarta.jms.MapMessage}
 * (the body shape broadcast by {@code MessageService.sendStatusUpdate}): Twitter promulgation is triggered
 * <em>only</em> for a {@code PUBLISHED} status, the message {@code UID} is forwarded verbatim, every other
 * status is ignored, and any decoding failure is swallowed rather than propagated.
 *
 * <p>No broker and no Quarkus container are involved: the listener is exercised as a plain object with a
 * hand-written recording {@link TwitterPromulgationService} double (the project uses no mocking framework)
 * and the in-memory {@link FakeMapMessage}. The actual broker round-trip is covered separately by
 * {@code MessageStatusTopicITTest} / {@code MessageStatusPublishITTest}.
 *
 * <p>NB: under Quarkus this listener is an EJB {@code @MessageDriven} bean that ArC does not wire, so it
 * never actually receives broker messages — see docs/potential-errors.md (JMS1). The decode/route logic
 * tested here is nonetheless the behaviour a Spring port must preserve, which is why it is pinned.
 */
public class TwitterMessageListenerTest {

    private RecordingTwitterPromulgationService promulgationService;
    private TwitterMessageListener listener;

    @BeforeEach
    public void setUp() {
        promulgationService = new RecordingTwitterPromulgationService();
        // PORT: the listener now takes its collaborators via a package-private constructor
        // (`private final` fields) instead of `@Inject` field injection. The recording double is
        // passed as the twitterPromulgationService argument, exactly as the test wired it before.
        // The connectionFactory dependency is only used by run() (the broker poll loop), which the
        // onMessage routing tests never exercise, so we pass null to preserve the prior behaviour
        // (the old @Inject field was simply never set by this test).
        listener = new TwitterMessageListener(promulgationService, null);
        // PORT: the listener's `log` is now `private static final` (Spring port) and can no longer
        // be replaced with a test logger. The listener swallows all Throwables internally, so its
        // logging has no observable effect on the routing contract under test; we simply let the
        // real logger run. The error-swallowing behaviour is still asserted via the
        // nonMapMessageIsSwallowed / missingStatusIsIgnoredWithoutError cases below.
    }

    @Test
    public void publishedStatusTriggersPromulgationWithTheMessageUid() {
        listener.onMessage(FakeMapMessage.statusUpdate(42, "uid-published", "PUBLISHED", "VERIFIED"));

        assertEquals(1, promulgationService.promulgatedUids.size(),
                "a PUBLISHED status update must trigger exactly one promulgation");
        assertEquals("uid-published", promulgationService.promulgatedUids.get(0),
                "the message UID must be forwarded to the promulgation service");
    }

    @Test
    public void verifiedStatusIsIgnored() {
        listener.onMessage(FakeMapMessage.statusUpdate(1, "uid-verified", "VERIFIED", "DRAFT"));
        assertTrue(promulgationService.promulgatedUids.isEmpty(),
                "only PUBLISHED must promulgate; VERIFIED must be ignored");
    }

    @Test
    public void otherNonPublishedStatusesAreIgnored() {
        for (Status status : new Status[]{Status.DRAFT, Status.CANCELLED, Status.EXPIRED, Status.DELETED}) {
            listener.onMessage(FakeMapMessage.statusUpdate(1, "uid-" + status, status.name(), "PUBLISHED"));
        }
        assertTrue(promulgationService.promulgatedUids.isEmpty(),
                "no non-PUBLISHED status may trigger promulgation");
    }

    @Test
    public void missingStatusIsIgnoredWithoutError() {
        // A malformed broadcast with no STATUS key: getString("STATUS") returns null, and
        // Status.PUBLISHED.name().equals(null) is false, so nothing is promulgated and nothing throws.
        listener.onMessage(FakeMapMessage.statusUpdate(7, "uid-no-status", null, "VERIFIED"));
        assertTrue(promulgationService.promulgatedUids.isEmpty(),
                "a missing STATUS must be treated as not-published");
    }

    @Test
    public void nonMapMessageIsSwallowed() {
        // The listener casts the incoming message to MapMessage; a non-MapMessage triggers a
        // ClassCastException that the catch(Throwable) must swallow (no promulgation, no propagation).
        listener.onMessage(new UnsupportedJmsMessage() { });
        assertTrue(promulgationService.promulgatedUids.isEmpty(),
                "a non-MapMessage must not trigger promulgation");
    }

    /** Records every UID handed to {@code checkPromulgateMessage} instead of contacting Twitter. */
    private static final class RecordingTwitterPromulgationService extends TwitterPromulgationService {
        final List<String> promulgatedUids = new ArrayList<>();

        // PORT: TwitterPromulgationService now takes its collaborators via a package-private
        // constructor instead of @Inject field injection, so the implicit no-arg super() no longer
        // exists. This double overrides checkPromulgateMessage and never uses those collaborators,
        // so they stay null exactly as field injection left them in the old `new` construction.
        RecordingTwitterPromulgationService() {
            super(null, null, null);
        }

        @Override
        public void checkPromulgateMessage(String messageUid) {
            promulgatedUids.add(messageUid);
        }
    }
}
