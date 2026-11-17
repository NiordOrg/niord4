/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Calendar;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.test.DeliveryFixtures.pastDate;
import static org.niord.test.DeliveryFixtures.uniq;

/**
 * Integration tests for the scheduled message status maintenance performed by
 * {@link ScheduledMessageService}, run against the seeded database of the {@link ITTest} infrastructure.
 *
 * <p>{@link ScheduledMessageService} has two package-private {@code @Scheduled} cron methods that normally fire once
 * a minute, each the call of a package-private seam with every active domain: {@code publishDueMessages}, which
 * promotes {@code VERIFIED} messages whose {@code publishDateFrom} is in the past to {@code PUBLISHED}, and
 * {@code expireDueMessages}, which moves {@code PUBLISHED} messages whose {@code publishDateTo} is in the past to
 * {@code EXPIRED}. This test lives in the same package and calls the seams with a domain of its own, so it asserts
 * their effect without waiting for the timer, and never touches the committed messages of other domains.
 *
 * <p>Each sweep changes each message in a transaction of its own (SCHED2), so a fixture written in the test's own
 * transaction would be invisible to it: the fixtures are committed by {@link DeliveryFixtures} into a message series
 * and a domain of their own, and removed after each test. Every published fixture is dated in year 2001
 * ({@link DeliveryFixtures#PAST_YEAR}), so the publish flow only ever bumps that year's sequence of the test's own
 * series.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ScheduledMessageServiceITTest extends ITTest {

    @Autowired
    ScheduledMessageService scheduledMessageService;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    /** A fixed FUTURE publish date (year 2999) used to assert that not-yet-due messages are untouched. */
    private static final Date FUTURE_PUBLISH_DATE = dateOfYear(2999);

    private DeliveryFixtures fixtures;

    /** This test's own NW series, numbered yearly */
    private String series;

    /** This test's own domain, of {@link #series} only: the one domain the sweeps are called with */
    private String domain;

    @BeforeEach
    void ownSeriesAndDomain() {
        fixtures = new DeliveryFixtures(context, em);
        String n = uniq();
        series = fixtures.createYearlyNwSeries("it-sms-" + n, "SMS");
        domain = fixtures.createDomain("it-sms-d-" + n, series);
    }

    @AfterEach
    void removeTheFixtures() {
        fixtures.removeAll();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private static Date dateOfYear(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    private void publishSweep() {
        assertTrue(scheduledMessageService.publishDueMessages(Set.of(domain)).isEmpty(), "no publication failed");
    }

    private void expirySweep() {
        assertTrue(scheduledMessageService.expireDueMessages(Set.of(domain)).isEmpty(), "no expiry failed");
    }

    // ------------------------------------------------------------------------------------------- //
    // publishDueMessages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publishesVerifiedMessageWithPastPublishFromDate() {
        String uid = fixtures.verified(series, "IT sms publish " + uniq(), pastDate());
        assertEquals(Status.VERIFIED, fixtures.statusOf(uid), "precondition: the message must start out VERIFIED");

        publishSweep();

        assertEquals(Status.PUBLISHED, fixtures.statusOf(uid),
                "a VERIFIED message whose publishDateFrom is in the past must be published");
    }

    @Test
    void doesNotPublishVerifiedWithFuturePublishFromDate() {
        String uid = fixtures.verified(series, "IT sms future " + uniq(), FUTURE_PUBLISH_DATE);

        publishSweep();

        assertEquals(Status.VERIFIED, fixtures.statusOf(uid),
                "a VERIFIED message whose publishDateFrom is in the future must stay VERIFIED");
    }

    @Test
    void doesNotPublishDraftMessages() {
        // A DRAFT message carries a past publishDateFrom but must not be published, because the scheduler only
        // promotes VERIFIED messages.
        String uid = fixtures.draft(series, "IT sms draft " + uniq(), pastDate());
        assertEquals(Status.DRAFT, fixtures.statusOf(uid), "precondition: the message must start out DRAFT");

        publishSweep();

        assertEquals(Status.DRAFT, fixtures.statusOf(uid),
                "a DRAFT message must not be published regardless of its publishDateFrom");
    }

    // ------------------------------------------------------------------------------------------- //
    // expireDueMessages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void expiresPublishedMessageWithPastPublishToDate() {
        String uid = fixtures.published(series, "IT sms expire " + uniq(), pastDate());
        assertEquals(Status.PUBLISHED, fixtures.statusOf(uid), "precondition: the message must start out PUBLISHED");

        expirySweep();

        assertEquals(Status.EXPIRED, fixtures.statusOf(uid),
                "a PUBLISHED message whose publishDateTo is in the past must be expired");
    }

    @Test
    void doesNotExpirePublishedWithFuturePublishToDate() {
        String uid = fixtures.published(series, "IT sms future end " + uniq(), FUTURE_PUBLISH_DATE);

        expirySweep();

        assertEquals(Status.PUBLISHED, fixtures.statusOf(uid),
                "a PUBLISHED message whose publishDateTo is in the future must stay PUBLISHED");
    }

    @Test
    void doesNotExpirePublishedWithNullPublishToDate() {
        String uid = fixtures.published(series, "IT sms no end " + uniq(), null);

        expirySweep();

        assertEquals(Status.PUBLISHED, fixtures.statusOf(uid),
                "a PUBLISHED message with no publishDateTo must never be expired");
    }
}
