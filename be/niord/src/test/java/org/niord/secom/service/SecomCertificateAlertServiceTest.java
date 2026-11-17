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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;
import org.niord.secom.service.SecomCertificateAlertService.LastAlert;
import org.niord.secom.service.SecomCertificateAlertService.Tier;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.secom.service.SecomCertificateAlertService.daysToExpiry;
import static org.niord.secom.service.SecomCertificateAlertService.shouldMail;
import static org.niord.secom.service.SecomCertificateAlertService.tier;

/**
 * Unit tests for the certificate expiry-alert logic: the floor-semantics day count (negative
 * means expired, which drives the UI marker), the warn/critical/expired tiering and the
 * mail dedup rules (entry, escalation, weekly cadence, certificate rotation).
 */
public class SecomCertificateAlertServiceTest {

    private static final Date NOW = new Date(1_000_000_000_000L);
    private static final long HOUR = 60L * 60 * 1000;

    private static Date offsetHours(long hours) {
        return new Date(NOW.getTime() + hours * HOUR);
    }

    @Test
    public void daysToExpiryUsesFloorSemantics() {
        assertEquals(1, daysToExpiry(offsetHours(36), NOW));
        assertEquals(0, daysToExpiry(offsetHours(12), NOW), "less than a day left counts as 0");
        assertEquals(0, daysToExpiry(NOW, NOW));
        assertEquals(-1, daysToExpiry(new Date(NOW.getTime() - 1), NOW), "any past notAfter is negative");
        assertEquals(-2, daysToExpiry(offsetHours(-25), NOW));
    }

    @Test
    public void tiersFollowTheWarnWindowAndEscalationThresholds() {
        assertEquals(Tier.OK, tier(61, 60));
        assertEquals(Tier.WARN, tier(60, 60));
        assertEquals(Tier.WARN, tier(15, 60));
        assertEquals(Tier.CRITICAL, tier(14, 60));
        assertEquals(Tier.CRITICAL, tier(0, 60));
        assertEquals(Tier.EXPIRED, tier(-1, 60));

        // A shrunk warn window is honored...
        assertEquals(Tier.OK, tier(10, 5));
        // ...but expiry always alerts
        assertEquals(Tier.EXPIRED, tier(-1, 0));
    }

    @Test
    public void dedupMailsOnEntryEscalationCadenceAndRotation() {
        Date mailedAt = NOW;
        LastAlert warned = new LastAlert("fp-1", Tier.WARN, mailedAt);

        assertTrue(shouldMail(null, "fp-1", Tier.WARN, NOW), "first alert always mails");
        assertFalse(shouldMail(warned, "fp-1", Tier.WARN, offsetHours(24)), "same tier next day is deduplicated");
        assertTrue(shouldMail(warned, "fp-1", Tier.WARN, offsetHours(7 * 24)), "weekly reminder within the tier");
        assertTrue(shouldMail(warned, "fp-1", Tier.CRITICAL, offsetHours(24)), "escalation beats the cadence");
        assertTrue(shouldMail(warned, "fp-1", Tier.EXPIRED, offsetHours(1)));
        assertTrue(shouldMail(warned, "fp-2", Tier.WARN, offsetHours(1)), "a rotated certificate resets the dedup");
    }
}
