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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TZ2: a message is numbered in the year of its publish date in the zone of the domain its series belongs to,
 * not in the JVM's zone. Each test publishes through the real path ({@code MessageService.updateStatus}, which
 * calls {@code MessageSeriesService.updateMessageIdsFromMessageSeries}) and reads the row back, so the save
 * callback that syncs the year on every save is part of what is checked.
 * <p>
 * The JVM's zone is whatever the machine runs in (Failsafe sets none). The Nuuk cases fail without the fix in
 * a UTC or a Copenhagen JVM alike; the Copenhagen case fails without it in a UTC JVM, the zone the deployments
 * run in.
 * <p>
 * Every test is {@code @Transactional} and rolls back. The number sequence a publish draws from is written in a
 * transaction of its own and stays, so every test uses a series id of its own, which no other test reads.
 */
class MessageSeriesYearZoneITTest extends ITTest {

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final ZoneId NUUK = ZoneId.of("America/Nuuk");
    private static final String SHORT_FORMAT = "NW-${number-3-digits}-${year-2-digits}";

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    /** A new NW series of its own, with the given number sequence and the short format above. */
    private MessageSeries newSeries(NumberSequenceType type) {
        MessageSeries series = new MessageSeries("it-tz2-" + UUID.randomUUID().toString().substring(0, 8));
        series.setMainType(MainType.NW);
        series.setNumberSequenceType(type);
        series.setShortFormat(SHORT_FORMAT);
        return messageSeriesService.createMessageSeries(series);
    }

    /** A new domain in the given zone that holds the given series. */
    private Domain newDomain(String timeZone, MessageSeries series) {
        Domain domain = new Domain();
        domain.setDomainId("it-tz2-" + UUID.randomUUID().toString().substring(0, 8));
        domain.setName("TZ2 test domain");
        domain.setTimeZone(timeZone);
        domain.getMessageSeries().add(series);
        em.persist(domain);
        em.flush();
        return domain;
    }

    /** Creates a draft in the series, publishes it (DRAFT, VERIFIED, PUBLISHED) and reads it back. */
    private Message publish(MessageSeries series, Date publishDate, Integer number) throws Exception {
        Message msg = new Message();
        MessageSeries ref = new MessageSeries(series.getSeriesId());
        ref.setMainType(MainType.NW);
        msg.setMessageSeries(ref);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(publishDate);
        msg.setNumber(number);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle("TZ2");
        Message created = messageService.createMessage(msg);
        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        return reload(created.getId());
    }

    private Message reload(Integer id) {
        em.flush();
        em.clear();
        return em.find(Message.class, id);
    }

    private static Date at(LocalDateTime local, ZoneId zone) {
        return Date.from(local.atZone(zone).toInstant());
    }

    @Test
    @Transactional
    void aMessagePublishedAtLocalNewYear_inACopenhagenDomain_isNumberedInTheNewYear() throws Exception {
        // 00:30 on 1 January 2026 in Copenhagen is 23:30 on 31 December 2025 in UTC.
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        newDomain("Europe/Copenhagen", series);

        Message published = publish(series, at(LocalDateTime.of(2026, 1, 1, 0, 30), COPENHAGEN), null);

        assertEquals(2026, published.year);
        assertEquals("NW-001-26", published.getShortId());
        assertEquals("001/26", published.computeNumberYearId());
    }

    @Test
    @Transactional
    void aMessagePublishedOnNewYearsEve_inANuukDomain_isNumberedInTheOldYear() throws Exception {
        // 22:30 on 31 December 2025 in Nuuk is 00:30 on 1 January 2026 in UTC, 01:30 in Copenhagen.
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        newDomain("America/Nuuk", series);

        Message published = publish(series, at(LocalDateTime.of(2025, 12, 31, 22, 30), NUUK), null);

        assertEquals(2025, published.year);
        assertEquals("NW-001-25", published.getShortId());
        assertEquals("001/25", published.computeNumberYearId());
    }

    @Test
    @Transactional
    void theZonedYearSurvivesALaterSave_andFollowsAChangedPublishDate() throws Exception {
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        newDomain("America/Nuuk", series);
        Date nuukNewYearsEve = at(LocalDateTime.of(2025, 12, 31, 22, 30), NUUK);
        Message published = publish(series, nuukNewYearsEve, null);
        Integer id = published.getId();

        // A later save of the loaded message (an editor's change of the text) keeps the year
        messageService.saveMessage(published);
        Message saved = reload(id);
        assertEquals(2025, saved.year, "a save must not read the year again in the JVM's zone");

        // A changed publish date gives a new year
        saved.setPublishDateFrom(at(LocalDateTime.of(2026, 6, 15, 12, 0), NUUK));
        messageService.saveMessage(saved);
        assertEquals(2026, reload(id).year);
    }

    @Test
    @Transactional
    void aSeriesWithoutAutomaticNumbers_takesTheYearOfTheDomainZone() throws Exception {
        // A MANUAL series: the editor gives the number, the short id is made from the format at publishing
        MessageSeries series = newSeries(NumberSequenceType.MANUAL);
        newDomain("America/Nuuk", series);

        Message published = publish(series, at(LocalDateTime.of(2025, 12, 31, 22, 30), NUUK), 7);

        assertEquals(2025, published.year);
        assertEquals("NW-007-25", published.getShortId());
        assertEquals("007/25", published.computeNumberYearId());
    }

    @Test
    @Transactional
    void aSeriesInNoDomain_takesTheYearOfTheJvmZone() throws Exception {
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        Date nuukNewYearsEve = at(LocalDateTime.of(2025, 12, 31, 22, 30), NUUK);
        int jvmYear = nuukNewYearsEve.toInstant().atZone(ZoneId.systemDefault()).getYear();

        Message published = publish(series, nuukNewYearsEve, null);

        assertEquals(jvmYear, published.year);
        assertEquals(String.format("NW-001-%02d", jvmYear % 100), published.getShortId());
        assertEquals(ZoneId.systemDefault(), messageSeriesService.seriesZone(series));
    }

    /**
     * R2-3: "Compute next number" counts the year the way publishing numbers it, in the zone of the series' domain.
     * Kiritimati is UTC+14, far from any JVM's zone a developer or CI runs in, so the result does not depend on it:
     * 15:00 UTC on 31 December 2019 is 05:00 on 1 January 2020 there, a message of the 2020 sequence. The date is in
     * the past: publishing moves a future publish date to the time of publishing.
     */
    @Test
    @Transactional
    void computeNextMessageNumber_countsTheYearInTheDomainZone() throws Exception {
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        newDomain("Pacific/Kiritimati", series);

        Message published = publish(series, Date.from(java.time.Instant.parse("2019-12-31T15:00:00Z")), 7);
        assertEquals(2020, published.year);

        assertEquals(8, messageSeriesService.computeNextMessageNumber(series.getSeriesId(), 2020),
                "the message numbered in 2020 counts in 2020");
        assertEquals(1, messageSeriesService.computeNextMessageNumber(series.getSeriesId(), 2019),
                "and not in 2019");
    }

    /**
     * An editor's change of a published, numbered message's publish date reads the year again in the zone of the
     * series' domain, not in the JVM's. Kiritimati (UTC+14) is far from any JVM's zone a developer or CI runs in:
     * 15:00 UTC on 31 December 2019 is 05:00 on 1 January 2020 there.
     */
    @Test
    @Transactional
    void anEditOfThePublishDate_readsTheYearInTheDomainZone() throws Exception {
        MessageSeries series = newSeries(NumberSequenceType.YEARLY);
        newDomain("Pacific/Kiritimati", series);
        Message published = publish(series, Date.from(java.time.Instant.parse("2019-06-01T00:00:00Z")), null);
        assertEquals(2019, published.year);
        Integer id = published.getId();

        Message template = new Message();
        template.setUid(published.getUid());
        template.setStatus(Status.PUBLISHED);
        MessageSeries ref = new MessageSeries(series.getSeriesId());
        ref.setMainType(MainType.NW);
        template.setMessageSeries(ref);
        template.setType(Type.COASTAL_WARNING);
        template.setNumber(published.getNumber());
        template.setShortId(published.getShortId());
        template.setAutoTitle(false);
        template.setPublishDateFrom(Date.from(java.time.Instant.parse("2019-12-31T15:00:00Z")));
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setDetails("details");
        template.addPart(part);
        template.createDesc("en").setTitle("TZ edit");
        em.clear();

        messageService.updateMessage(template);

        Message edited = reload(id);
        assertEquals(2020, edited.year, "the year of the new publish date in Kiritimati, not in the JVM's zone");
        assertEquals(published.getShortId(), edited.getShortId(), "the short id is not changed by the edit");
    }

    @Test
    @Transactional
    void theSeriesZone_isTheDomainsZone_onlyWhenItIsOneReadableZone() {
        ZoneId jvm = ZoneId.systemDefault();

        MessageSeries copenhagenTwice = newSeries(NumberSequenceType.YEARLY);
        newDomain("Europe/Copenhagen", copenhagenTwice);
        newDomain("Europe/Copenhagen", copenhagenTwice);
        assertEquals(COPENHAGEN, messageSeriesService.seriesZone(copenhagenTwice));

        MessageSeries twoZones = newSeries(NumberSequenceType.YEARLY);
        newDomain("Europe/Copenhagen", twoZones);
        newDomain("America/Nuuk", twoZones);
        assertEquals(jvm, messageSeriesService.seriesZone(twoZones), "zones that differ fall back to the JVM's");

        MessageSeries unreadable = newSeries(NumberSequenceType.YEARLY);
        newDomain("Not/AZone", unreadable);
        assertEquals(jvm, messageSeriesService.seriesZone(unreadable), "an unreadable zone falls back to the JVM's");

        assertEquals(jvm, messageSeriesService.seriesZone(null));
        assertEquals(jvm, messageSeriesService.seriesZone(new MessageSeries("it-tz2-unknown-series")));
    }
}
