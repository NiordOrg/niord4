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
package org.niord.test;

import jakarta.persistence.EntityManager;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.area.AreaType;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.schedule.FiringPeriod;
import org.niord.core.schedule.FiringSchedule;
import org.niord.core.schedule.FiringScheduleService;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Committed fixtures for the tests of the unattended delivery runs: the publication and expiry sweeps of
 * {@code ScheduledMessageService} and the hourly firing-exercise run.
 *
 * <p>Those runs change each message (or each firing area) in a transaction of its own (SCHED2, FIRING1 in
 * be/docs/potential-errors.md), so a fixture written inside a test's own transaction is invisible to them: the
 * fixtures here are committed with {@link Tx}, into message series, domains and firing areas of their own, and
 * {@link #removeAll()} (called from the test class's {@code @AfterEach}) removes them again: the firing schedules,
 * the firing areas with their periods and the messages that carry them, the messages of the series created here
 * with their history rows, the domains and the series. A test sweeps only its own domains (the sweeps' seams take
 * the domain ids), never every domain: the committed seed messages and other tests' committed messages would be
 * published or expired.
 *
 * <p>Every message created here is dated in {@link #PAST_YEAR}, so publishing it numbers in that year's sequence of
 * its own series and never touches the current year's.
 *
 * <p>The services are looked up in the given context, so a test class's {@code @MockitoSpyBean} spies are the ones
 * used.
 */
public final class DeliveryFixtures {

    /** The year every fixture message is dated in, and numbers in when it is published */
    public static final int PAST_YEAR = 2001;

    private final ApplicationContext context;
    private final EntityManager em;

    private final List<String> createdSeriesIds = new ArrayList<>();
    private final List<String> createdDomainIds = new ArrayList<>();
    private final List<Integer> createdScheduleIds = new ArrayList<>();
    private final List<Integer> createdAreaIds = new ArrayList<>();

    /**
     * @param context the test's application context
     * @param em      the test's (shared) entity manager
     */
    public DeliveryFixtures(ApplicationContext context, EntityManager em) {
        this.context = context;
        this.em = em;
    }

    private <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    // ------------------------------------------------------------------------------------------- //
    // Dates and names
    // ------------------------------------------------------------------------------------------- //

    /** A short unique suffix for the ids and names of one test's fixtures */
    public static String uniq() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** A date in {@link #PAST_YEAR}: due for publication or expiry, and numbered in that year */
    public static Date pastDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(PAST_YEAR, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** The given hour of the day the given number of days from today */
    public static Date futureDay(int days, int hour) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, days);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // Series, domains and messages
    // ------------------------------------------------------------------------------------------- //

    /** Creates and commits a message series, removed by {@link #removeAll()} */
    public String createSeries(String seriesId, MainType mainType, NumberSequenceType numbering, String shortFormat) {
        Tx.requiringNew().run(() -> {
            MessageSeries series = new MessageSeries(seriesId);
            series.setMainType(mainType);
            series.setNumberSequenceType(numbering);
            series.setShortFormat(shortFormat);
            bean(MessageSeriesService.class).createMessageSeries(series);
        });
        createdSeriesIds.add(seriesId);
        return seriesId;
    }

    /** Creates and commits a YEARLY numbered NW series with a short-id format of the given prefix */
    public String createYearlyNwSeries(String seriesId, String prefix) {
        return createSeries(seriesId, MainType.NW, NumberSequenceType.YEARLY,
                prefix + "-${number-3-digits}-${year-2-digits}");
    }

    /** Creates and commits an active domain of the given series in Europe/Copenhagen, removed by {@link #removeAll()} */
    public String createDomain(String domainId, String... seriesIds) {
        return createDomainInZone(domainId, "Europe/Copenhagen", seriesIds);
    }

    /** Creates and commits an active domain of the given series in the given zone, removed by {@link #removeAll()} */
    public String createDomainInZone(String domainId, String timeZone, String... seriesIds) {
        Tx.requiringNew().run(() -> {
            Domain domain = new Domain();
            domain.setDomainId(domainId);
            domain.setName("IT delivery " + domainId);
            domain.setTimeZone(timeZone);
            List<MessageSeries> series = new ArrayList<>();
            for (String id : seriesIds) {
                series.add(new MessageSeries(id));
            }
            domain.setMessageSeries(series);
            bean(DomainService.class).createDomain(domain, false);
        });
        createdDomainIds.add(domainId);
        return domainId;
    }

    /** A detached draft with one DETAILS part and an English title and subject */
    public static Message newDraft(String seriesId, Type type, String title, Date publishFrom) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(publishFrom);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject " + title);
        pd.setDetails("details " + title);
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    /** A committed DRAFT coastal warning with the given publish-from date */
    public String draft(String seriesId, String title, Date publishFrom) {
        return Tx.requiringNew().call(() -> bean(MessageService.class)
                .createMessage(newDraft(seriesId, Type.COASTAL_WARNING, title, publishFrom)).getUid());
    }

    /** A committed VERIFIED coastal warning with the given publish-from date */
    public String verified(String seriesId, String title, Date publishFrom) {
        return Tx.requiringNew().call(() -> {
            MessageService messageService = bean(MessageService.class);
            Message m = messageService.createMessage(newDraft(seriesId, Type.COASTAL_WARNING, title, publishFrom));
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            return m.getUid();
        });
    }

    /** A committed VERIFIED coastal warning due for publication (its publish-from date is in {@link #PAST_YEAR}) */
    public String verifiedDue(String seriesId, String title) {
        return verified(seriesId, title, pastDate());
    }

    /** A committed PUBLISHED coastal warning, published from {@link #PAST_YEAR}, with the given publish-to date */
    public String published(String seriesId, String title, Date publishTo) {
        String uid = Tx.requiringNew().call(() -> {
            MessageService messageService = bean(MessageService.class);
            Message m = messageService.createMessage(newDraft(seriesId, Type.COASTAL_WARNING, title, pastDate()));
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            messageService.updateStatus(m.getUid(), Status.PUBLISHED);
            return m.getUid();
        });
        Tx.requiringNew().run(() -> bean(MessageQueryService.class).findByUid(uid).setPublishDateTo(publishTo));
        return uid;
    }

    /** A committed PUBLISHED coastal warning whose publish-to date has passed: due for expiry */
    public String publishedDueForExpiry(String seriesId, String title) {
        return published(seriesId, title, pastDate());
    }

    /** The committed status of the message */
    public Status statusOf(String uid) {
        return Tx.requiringNew().call(() -> bean(MessageQueryService.class).findByUid(uid).getStatus());
    }

    /** The committed number of the message */
    public Integer numberOf(String uid) {
        return Tx.requiringNew().call(() -> bean(MessageQueryService.class).findByUid(uid).getNumber());
    }

    /** The statuses of the message's committed history entries, the newest first */
    public List<Status> historyStatuses(String uid) {
        return Tx.requiringNew().call(() -> em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                .setParameter("messageId", bean(MessageQueryService.class).findByUid(uid).getId())
                .getResultList().stream()
                .map(MessageHistory::getStatus)
                .toList());
    }

    /** The next number the series hands out in {@link #PAST_YEAR} */
    public int nextNumber(String seriesId) {
        return bean(MessageSeriesService.class).getNextMessageNumber(seriesId, PAST_YEAR);
    }

    // ------------------------------------------------------------------------------------------- //
    // The firing-exercise run
    // ------------------------------------------------------------------------------------------- //

    /**
     * The message series and domains of a firing schedule of its own: an NM source series in a source domain, whose
     * published messages in a firing area are the templates, and an NW target series in a target domain, which the
     * run issues the exercises into.
     */
    public record FiringDomains(String sourceSeries, String targetSeries, String sourceDomain, String targetDomain) {
    }

    /** Commits a source and a target series, unnumbered, each in a domain of its own in Europe/Copenhagen */
    public FiringDomains firingDomains() {
        return firingDomains(NumberSequenceType.NONE, "Europe/Copenhagen");
    }

    /**
     * Commits a source series (NM, unnumbered) in a domain of its own, and a target series (NW, numbered as given) in
     * a domain of its own in the given zone, the zone the exercises' time texts are written in.
     */
    public FiringDomains firingDomains(NumberSequenceType targetNumbering, String targetTimeZone) {
        String n = uniq();
        String source = createSeries("it-fe-src-" + n, MainType.NM, NumberSequenceType.NONE, null);
        String target = createSeries("it-fe-tgt-" + n, MainType.NW, targetNumbering,
                targetNumbering == NumberSequenceType.NONE ? null : "FE-${number-3-digits}-${year-2-digits}");
        String sourceDomain = createDomain("it-fe-ds-" + n, source);
        String targetDomain = createDomainInZone("it-fe-dt-" + n, targetTimeZone, target);
        return new FiringDomains(source, target, sourceDomain, targetDomain);
    }

    /** Commits an active root firing area with an English name, removed (with its periods and messages) later */
    public Area firingArea(String name) {
        Area area = Tx.requiringNew().call(() -> {
            Area a = new Area();
            a.setType(AreaType.FIRING_AREA);
            a.checkCreateDesc("en").setName(name);
            return bean(AreaService.class).createArea(a, null);
        });
        createdAreaIds.add(area.getId());
        return area;
    }

    /**
     * Commits a PUBLISHED template in the source series, carrying the firing area (no area when it is null)
     *
     * @return the template's uid
     */
    public String firingTemplate(String sourceSeries, Area area, String title) {
        return Tx.requiringNew().call(() -> {
            Message template = newDraft(sourceSeries, Type.TEMPORARY_NOTICE, title, pastDate());
            template.setStatus(Status.PUBLISHED);
            if (area != null) {
                template.getAreas().add(area);
            }
            return bean(MessageService.class).createMessage(template).getUid();
        });
    }

    /**
     * Commits a firing period of the area
     *
     * @return the period's id
     */
    public Integer firingPeriod(Area area, Date from, Date to) {
        return Tx.requiringNew().call(() -> {
            FiringPeriod period = new FiringPeriod();
            period.setArea(area);
            period.setFromDate(from);
            period.setToDate(to);
            return bean(FiringScheduleService.class).addFiringPeriod(period).getId();
        });
    }

    /**
     * Commits a firing period of the area as it is given, past the service's checks: a period whose end is at or
     * before its start, as the schedule editor stored them before FIRING2 was fixed (the rows a deployment keeps)
     *
     * @return the period's id
     */
    public Integer storedFiringPeriod(Area area, Date from, Date to) {
        return Tx.requiringNew().call(() -> {
            FiringPeriod period = new FiringPeriod();
            period.setArea(em.find(Area.class, area.getId()));
            period.setFromDate(from);
            period.setToDate(to);
            em.persist(period);
            em.flush();
            return period.getId();
        });
    }

    /**
     * Commits a firing schedule from the source domain to the target domain and series, removed by
     * {@link #removeAll()}
     *
     * @return the schedule's id, which the run's seam takes
     */
    public Integer firingSchedule(FiringDomains domains, int days, boolean active) {
        Integer id = Tx.requiringNew().call(() -> {
            FiringSchedule schedule = new FiringSchedule();
            schedule.setDomain(new Domain());
            schedule.getDomain().setDomainId(domains.sourceDomain());
            schedule.setTargetDomain(new Domain());
            schedule.getTargetDomain().setDomainId(domains.targetDomain());
            schedule.setTargetMessageSeries(new MessageSeries(domains.targetSeries()));
            schedule.setScheduleDays(days);
            schedule.setActive(active);
            return bean(FiringScheduleService.class).createFiringSchedule(schedule).getId();
        });
        createdScheduleIds.add(0, id);
        return id;
    }

    /** A firing schedule over a source domain of its own and a target domain of its own, two areas, two templates. */
    public record FiringFixture(String sourceSeries, String targetSeries, Area area1, Area area2, Integer scheduleId,
                                String template1, String template2) {
    }

    /**
     * Commits a firing schedule (active, 30 days) from a source domain of its own to a target domain and series of
     * its own, two firing areas with a published template each in the source series, and one firing period per
     * area, three and four days from today, 08:00 to 12:00.
     */
    public FiringFixture firingFixture() {
        String n = uniq();
        FiringDomains domains = firingDomains();
        Area area1 = firingArea("IT-FE fault area 1 " + n);
        Area area2 = firingArea("IT-FE fault area 2 " + n);
        String template1 = firingTemplate(domains.sourceSeries(), area1, "IT-FE template 1 " + n);
        String template2 = firingTemplate(domains.sourceSeries(), area2, "IT-FE template 2 " + n);
        firingPeriod(area1, futureDay(3, 8), futureDay(3, 12));
        firingPeriod(area2, futureDay(4, 8), futureDay(4, 12));
        Integer scheduleId = firingSchedule(domains, 30, true);
        return new FiringFixture(domains.sourceSeries(), domains.targetSeries(), area1, area2, scheduleId,
                template1, template2);
    }

    /** A committed exercise as the tests read it: the values read inside the transaction that loaded it */
    public record FiringExercise(String uid, Status status, MainType mainType, Type type, List<Integer> areaIds,
                                 List<Date[]> eventDates, Date publishDateTo, int timeParts, String timeTextEn) {

        /** Whether the exercise carries the area */
        public boolean carries(Area area) {
            return areaIds.contains(area.getId());
        }
    }

    /**
     * The committed exercises of the target series in the firing area with the given status, each with its time
     * part's event dates (sorted, from and to) and English time text
     */
    public List<FiringExercise> firingExercises(String targetSeries, Area area, Status status) {
        return Tx.requiringNew().call(() -> em.createQuery("select distinct m from Message m join m.areas a "
                                + "where m.messageSeries.seriesId = :series and a.id = :area and m.status = :status",
                        Message.class)
                .setParameter("series", targetSeries)
                .setParameter("area", area.getId())
                .setParameter("status", status)
                .getResultList().stream()
                .map(m -> {
                    List<MessagePart> timeParts = m.getParts().stream()
                            .filter(p -> p.getType() == MessagePartType.TIME)
                            .toList();
                    List<Date[]> dates = timeParts.stream()
                            .flatMap(p -> p.getEventDates().stream())
                            .sorted()
                            .map(di -> new Date[]{new Date(di.getFromDate().getTime()), new Date(di.getToDate().getTime())})
                            .toList();
                    String text = timeParts.isEmpty() || timeParts.get(0).getDesc("en") == null
                            ? null : timeParts.get(0).getDesc("en").getDetails();
                    return new FiringExercise(m.getUid(), m.getStatus(), m.getMainType(), m.getType(),
                            m.getAreas().stream().map(Area::getId).toList(), dates,
                            m.getPublishDateTo() == null ? null : new Date(m.getPublishDateTo().getTime()),
                            timeParts.size(), text);
                })
                .toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // Removal
    // ------------------------------------------------------------------------------------------- //

    /**
     * Removes everything created here, best effort, each part in a committed transaction of its own: a part that
     * cannot be removed is reported on standard error and the rest is still removed.
     */
    public void removeAll() {
        for (Integer id : createdScheduleIds) {
            try {
                Tx.requiringNew().run(() -> bean(FiringScheduleService.class).deleteFiringSchedule(id));
            } catch (Exception e) {
                System.err.println("DeliveryFixtures: firing schedule " + id + " left behind: " + e);
            }
        }
        createdScheduleIds.clear();

        // The firing areas go too, with their periods and the messages that carry them (the templates and the
        // exercises). A test class with a context of its own numbers its rows from a block of the shared sequences
        // of its own, above the block the suite's main context is still using: a firing area left behind is then an
        // area with a higher id than the main context's next one, and MessageSearchDedupAndIdITTest (which asserts
        // that a new area's id is above every existing one, a guard for a sequence seeded too low) fails after it.
        for (Integer areaId : createdAreaIds) {
            try {
                Tx.requiringNew().run(() -> {
                    Area area = em.find(Area.class, areaId);
                    if (area == null) {
                        return;
                    }
                    em.createNamedQuery("FiringPeriod.findByArea", FiringPeriod.class)
                            .setParameter("area", area).getResultList().forEach(em::remove);
                    for (Message m : em.createQuery("select distinct m from Message m join m.areas a where a.id = :id",
                            Message.class).setParameter("id", areaId).getResultList()) {
                        removeWithHistory(m);
                    }
                    em.remove(area);
                });
            } catch (Exception e) {
                System.err.println("DeliveryFixtures: firing area " + areaId + " left behind: " + e);
            }
        }
        createdAreaIds.clear();

        // The messages of the series created here, with their history rows
        for (String seriesId : createdSeriesIds) {
            try {
                Tx.requiringNew().run(() -> em.createQuery(
                                "select m from Message m where m.messageSeries.seriesId = :series", Message.class)
                        .setParameter("series", seriesId)
                        .getResultList()
                        .forEach(this::removeWithHistory));
            } catch (Exception e) {
                System.err.println("DeliveryFixtures: messages of series " + seriesId + " left behind: " + e);
            }
        }

        for (String domainId : createdDomainIds) {
            try {
                Tx.requiringNew().run(() -> bean(DomainService.class).deleteDomain(domainId));
            } catch (Exception e) {
                System.err.println("DeliveryFixtures: domain " + domainId + " left behind: " + e);
            }
        }
        createdDomainIds.clear();

        for (String seriesId : createdSeriesIds) {
            try {
                Tx.requiringNew().run(() -> bean(MessageSeriesService.class).deleteMessageSeries(seriesId));
            } catch (Exception e) {
                System.err.println("DeliveryFixtures: series " + seriesId + " left behind: " + e);
            }
        }
        createdSeriesIds.clear();
    }

    private void removeWithHistory(Message m) {
        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                .setParameter("messageId", m.getId()).getResultList().forEach(em::remove);
        em.remove(m);
    }
}
