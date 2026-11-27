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

package org.niord.core.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.Area;
import org.niord.core.area.AreaSearchParams;
import org.niord.core.area.AreaService;
import org.niord.core.area.AreaType;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.Domain;
import org.niord.core.geojson.FeatureService;
import org.niord.core.message.*;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.model.BaseEntity;
import org.niord.core.service.BaseService;
import org.niord.core.settings.SettingValueCheck;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.TimeUtils;
import org.niord.model.message.*;
import org.hibernate.Session;
import org.slf4j.Logger;

import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.niord.core.settings.Setting.Type.Integer;

/**
 * The Firing Exercise service generates firing exercise messages based on firing schedules.
 * <p>
 * The service will periodically load all active schedules and perform the following steps:
 * <ul>
 *     <li>Load all firing area messages from the source domain.</li>
 *     <li>Load all future firing periods for the firing areas of the source domain.</li>
 *     <li>Combine the firing area messages and firing periods to generate firing exercise message templates
 *          for the target message series.</li>
 *     <li>Fill out a textual time part of the firing exercise messages based on the time zone of the target domain.</li>
 *     <li>Compare the list of firing exercise message templates with the ones already published in the target domain,
 *          and cancel and publish new messages accordingly.</li>
 * </ul>
 */
@Service
@SuppressWarnings("unused")
public class FiringExerciseService extends BaseService implements SettingValueCheck {

    // Default number of schedule days to include in the firing exercise messages
        @Setting(value="firingExerciseScheduleDays",
            defaultValue="30",
            description="Default number of scheduled days to include in generated firing exercises.",
            type = Integer)
    private volatile int firingExerciseScheduleDays;

    /**
     * Refuses a save of {@code firingExerciseScheduleDays} below 1, as the firing schedule refuses its own days below 1:
     * the run's window would end before today, so the next run would cancel the current firing exercises of every
     * schedule without days of its own
     */
    @Override
    public String refusal(String key, Object value) {
        return "firingExerciseScheduleDays".equals(key)
                ? SettingValueCheck.outside(key, value, 1, java.lang.Integer.MAX_VALUE, "a number of days of at least 1")
                : null;
    }

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(FiringExerciseService.class);

    private final FiringScheduleService firingScheduleService;

    private final MessageService messageService;

    private final MessageSearchService messageSearchService;

    private final AreaService areaService;

    private final FeatureService featureService;

    private final DictionaryService dictionaryService;

    private final MessageTxRunner txRunner;

    FiringExerciseService(FiringScheduleService firingScheduleService, MessageService messageService, MessageSearchService messageSearchService, AreaService areaService, FeatureService featureService, DictionaryService dictionaryService, MessageTxRunner txRunner) {
        this.firingScheduleService = firingScheduleService;
        this.messageService = messageService;
        this.messageSearchService = messageSearchService;
        this.areaService = areaService;
        this.featureService = featureService;
        this.dictionaryService = dictionaryService;
        this.txRunner = txRunner;
    }

    /**
     * The key space of the advisory lock each schedule's run holds (two keys: this space and the schedule's id).
     * It is not the S-124 dataset lock's space ({@code 0x5124}), and a one-key lock (the defaults ledger's) is a
     * key space of its own in PostgreSQL.
     */
    static final int FIRING_RUN_LOCK_SPACE = 0x0F12E;

    /**
     * What one run left undone: the schedules whose run failed, the firing areas whose new exercise could not be
     * created (with the cancellation of the exercise it replaces), and the exercises that could not be cancelled.
     * Each is tried again by the next run, within the hour. A run where every count is 0 did everything.
     *
     * @param failedSchedules     the schedules skipped for this run, as their reads or a step outside an area failed
     * @param failedAreas         the firing areas whose exercise was not created or replaced
     * @param failedCancellations the exercises that should have been cancelled and were not
     */
    public record FiringRunResult(int failedSchedules, int failedAreas, int failedCancellations) {

        /** The number of changes that failed in the run */
        public int failures() {
            return failedSchedules + failedAreas + failedCancellations;
        }
    }

    /** The failures counted while a run goes on */
    private static final class RunCounts {
        int failedSchedules;
        int failedAreas;
        int failedCancellations;

        FiringRunResult result() {
            return new FiringRunResult(failedSchedules, failedAreas, failedCancellations);
        }
    }

    /**
     * Every hour, firing exercises are created and updated according to the active schedules.
     * TODO: Hourly update are used when the schedule is imported from a legacy system.
     * TODO: Once Niord is used for scheduling, chang to run every night at 3 AM
     * <p>
     * The schedule is the property {@code niord.schedule.firing-exercise-cron}, with the shipped value as its
     * default; the integration suite sets it to {@code -} so the run cannot touch the firing tests' committed
     * schedules. A run that left something undone says so in a WARN line ({@link #updateFiringExercises(Set)}).
     */
    @Scheduled(cron = "${niord.schedule.firing-exercise-cron:50 21 */1 * * ?}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void updateFiringExercises() {
        updateFiringExercises(null);
    }

    /**
     * Creates and updates the firing exercises of the given active schedules: the run behind the hourly job and the
     * "Update firing exercises" action, and the seam the integration tests call with schedules of their own.
     * <p>
     * The run joins no transaction of its caller. Each schedule is read in a transaction of its own, and each firing
     * area's change in a transaction of that area's own, through {@link MessageTxRunner}:
     * <ul>
     *     <li>an area's new exercise is created, and the current exercise it replaces cancelled, together: both
     *         commit, or neither does. When the new exercise cannot be created, the current one stays in force, and
     *         the area is tried again the next hour;</li>
     *     <li>only a current exercise whose area has no exercise in the new set is cancelled, each in a transaction of
     *         its own;</li>
     *     <li>an area whose periods in the window have all ended gets no new exercise. Its current exercise
     *         is left for the expiry sweep when its own publication end has passed, and cancelled when that end is
     *         still ahead (it announces a firing the schedule no longer holds);</li>
     *     <li>a period stored with its end at or before its start (an older row; a save now refuses one) is read as
     *         ending one day later, so that its warning stays published until its firing has ended; the stored row
     *         is not changed;</li>
     *     <li>a schedule whose reads fail (a failed template search among them) is skipped for this run, and nothing
     *         of it is cancelled: a failed search is not read as "no template". The other schedules run.</li>
     * </ul>
     * <p>
     * One schedule is run by one run at a time. The schedule's transaction first takes a transaction-level advisory
     * lock on the schedule ({@link #FIRING_RUN_LOCK_SPACE} and its id), before it reads anything, and holds it until
     * it ends, after its areas' transactions have committed. A second run of the schedule (the hourly job, the
     * editor's button pressed during it or twice, another replica) waits for the first, and then reads the exercises
     * the first one issued, so it does not issue them again. A run holds one schedule's lock at a time. The schedule's
     * transaction is exempt from an idle-in-transaction timeout of the database ({@link #lockSchedule}), so the lock
     * lasts the whole run.
     * <p>
     * What failed is counted in the answer, and named in one WARN line when anything did; each failure is also
     * logged where it happened.
     *
     * @param scheduleIds the schedules to run, or {@code null} for every active schedule
     * @return what the run left undone
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FiringRunResult updateFiringExercises(Set<Integer> scheduleIds) {

        Date today = TimeUtils.resetTime(new Date());
        RunCounts counts = new RunCounts();

        // Get active schedules
        List<Integer> activeScheduleIds = firingScheduleService.getFiringSchedules().stream()
                .filter(FiringSchedule::isActive)
                .map(FiringSchedule::getId)
                .filter(id -> scheduleIds == null || scheduleIds.contains(id))
                .collect(Collectors.toList());

        log.debug("Processing " + activeScheduleIds.size() + " active firing schedules");

        for (Integer scheduleId : activeScheduleIds) {
            try {
                txRunner.call(() -> {
                    lockSchedule(scheduleId);
                    FiringSchedule schedule = firingScheduleService.findById(scheduleId);
                    if (schedule == null) {
                        // Deleted since the list was read: nothing of it to run
                        return null;
                    }
                    updateFiringExercises(schedule, today, counts);
                    return null;
                });
            } catch (Exception e) {
                counts.failedSchedules++;
                log.error("Firing schedule " + scheduleId + " failed in this run; the exercises it had not reached"
                        + " are left as they are, and it runs again next hour", e);
            }
        }

        FiringRunResult result = counts.result();
        if (result.failures() > 0) {
            log.warn("Firing exercise run left " + result.failures() + " changes undone (" + result.failedSchedules()
                    + " schedules, " + result.failedAreas() + " areas, " + result.failedCancellations()
                    + " cancellations); they are tried again next hour");
        }
        return result;
    }

    /**
     * Takes the schedule's run lock until the caller's transaction ends: a transaction-level advisory lock keyed on
     * {@link #FIRING_RUN_LOCK_SPACE} and the schedule's id. The reads that follow are statements of their own, so
     * under READ COMMITTED they see what the previous holder committed.
     * <p>
     * The transaction then runs no statement while its areas' transactions work on other connections, so it first
     * switches off, for itself only ({@code SET LOCAL}), a database's {@code idle_in_transaction_session_timeout}: a
     * database that ends sessions idle in a transaction would otherwise end the schedule's session, and the lock with
     * it, in mid-run, and let a second run start beside the first.
     */
    private void lockSchedule(Integer scheduleId) {
        em.unwrap(Session.class).doWork(connection -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL idle_in_transaction_session_timeout = 0");
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, FIRING_RUN_LOCK_SPACE);
                ps.setInt(2, scheduleId);
                ps.execute();
            }
        });
    }

    /**
     * Creates and updates the firing exercises of one schedule, inside the schedule's own transaction. Each area's
     * change runs in a transaction of its own.
     *
     * @param schedule the schedule
     * @param today    the start of today, the start of the schedule's window
     * @param counts   the run's failure counts, which the areas' and the cancellations' failures are added to
     * @throws Exception when the schedule's reads fail; nothing of the schedule is then cancelled
     */
    private void updateFiringExercises(FiringSchedule schedule, Date today, RunCounts counts) throws Exception {

        // Get hold of the relevant firing areas
        List<Area> firingAreas = getFiringAreas(schedule.getDomain());

        // Get firing area message templates
        List<Message> firingAreaMessages = getFiringAreaMessages(
                schedule.getDomain(),
                null,
                firingAreas);

        // Find the firing periods. The read starts one day before the window: a period stored with its end at or
        // before its start is read as ending one day later, and on the day of its firing its stored end can
        // lie before the window (a local midnight east of the server's zone falls on the server's previous day)
        int days = schedule.getScheduleDays() != null ? schedule.getScheduleDays() : firingExerciseScheduleDays;
        Date endDate = TimeUtils.add(today, Calendar.DATE, days);
        List<FiringPeriod> storedPeriods = em.createNamedQuery("FiringPeriod.findByAreasAndDateInterval", FiringPeriod.class)
                .setParameter("areas", firingAreas)
                .setParameter("fromDate", TimeUtils.add(today, Calendar.DATE, -1))
                .setParameter("toDate", TimeUtils.endOfDay(endDate))
                .getResultList();
        List<RunPeriod> firingPeriods = runPeriods(storedPeriods, schedule.targetTimeZone(), today);

        // Create (un-persisted) firing exercise messages from the firing area templates and the firing periods
        Date now = new Date();
        GeneratedExercises generated = generateFiringExerciseMessages(
                schedule,
                firingAreaMessages,
                firingPeriods,
                now,
                counts);

        // Get the currently published firing exercise messages
        List<Message> currentFiringExerciseMessages = getFiringAreaMessages(
                schedule.getTargetDomain(),
                schedule.getTargetMessageSeries(),
                firingAreas);

        // Persist new messages if they are not already published
        generated.messages().forEach(message
                ->  checkCreateFiringExerciseMessage(message, currentFiringExerciseMessages, counts));

        // An area whose periods have all ended gets no new exercise. Its current exercise is left for the
        // expiry sweep when its own publication has ended too. One whose publication end is still ahead announces a
        // firing the schedule no longer holds, and is cancelled below, as when the area has no period left.
        currentFiringExerciseMessages.removeIf(m
                -> generated.endedAreaIds().contains(getFiringAreaForMessage(m).getId())
                && m.getPublishDateTo() != null
                && !m.getPublishDateTo().after(now));

        // An area whose exercise could not be built keeps its current exercise in force, as when its new exercise
        // cannot be created
        currentFiringExerciseMessages.removeIf(m
                -> generated.failedAreaIds().contains(getFiringAreaForMessage(m).getId()));

        // The messages left in the currentFiringExerciseMessages should be cancelled
        currentFiringExerciseMessages.forEach(m -> cancelCurrentFiringExerciseMessage(m, counts));
    }

    /**
     * Checks if the firing exercise message should be created by looking at any existing
     * messages.
     * <p>
     * The new exercise is created, and the current exercise of its area (when there is one) cancelled, in one
     * transaction of their own: when the creation fails, both roll back and the current exercise stays in force.
     * Whatever happens, the current exercise of the area is removed from the list of current messages (kept
     * unchanged, replaced, or kept because its replacement failed), so it is not cancelled at the end of the run.
     *
     * @param message the message to create if it has been updated
     * @param counts  the run's failure counts; a failed creation or replacement adds a failed area
     */
    private void checkCreateFiringExerciseMessage(Message message, List<Message> currentFiringExerciseMessages,
                                                  RunCounts counts) {
        Area firingArea = getFiringAreaForMessage(message);
        String areaName = firingArea.getDescs().get(0).getName();

        Message currentFiringExerciseMessage = currentFiringExerciseMessages.stream()
                .filter(m -> Objects.equals(firingArea.getId(), getFiringAreaForMessage(m).getId()))
                .findFirst()
                .orElse(null);

        // Handled here, whatever happens below: only an exercise whose area has no exercise in the new set is
        // cancelled at the end of the run
        if (currentFiringExerciseMessage != null) {
            currentFiringExerciseMessages.remove(currentFiringExerciseMessage);
            if (sameFiringPeriods(message, currentFiringExerciseMessage)) {
                return;
            }
        }

        try {
            log.info("Creating firing exercise message for area " + areaName);
            txRunner.call(() -> {
                messageService.createMessage(message);
                if (currentFiringExerciseMessage != null) {
                    log.info("Cancelling firing exercise message " + currentFiringExerciseMessage.getUid()
                            + ", replaced");
                    messageService.updateStatus(currentFiringExerciseMessage.getUid(), Status.CANCELLED);
                }
                return null;
            });
        } catch (Exception e) {
            counts.failedAreas++;
            if (currentFiringExerciseMessage != null) {
                log.error("Firing exercise of area " + areaName + " not replaced; the current one "
                        + currentFiringExerciseMessage.getUid() + " stays in force", e);
            } else {
                log.error("Firing exercise of area " + areaName + " not created", e);
            }
        }
    }

    /**
     * Cancels the given firing exercise message, in a transaction of its own
     * @param message the message to cancel
     * @param counts  the run's failure counts; a failed cancellation is added
     */
    private void cancelCurrentFiringExerciseMessage(Message message, RunCounts counts) {
        try {
            log.info("Cancelling firing exercise message " + message.getUid());
            txRunner.call(() -> messageService.updateStatus(message.getUid(), Status.CANCELLED));
        } catch (Exception e) {
            counts.failedCancellations++;
            log.error("Firing exercise " + message.getUid() + " not cancelled; it is tried again next hour", e);
        }
    }

    /**
     * Checks if the two messages defines the same firing periods
     * @param m1 the first message
     * @param m2 the second message
     * @return if the two messages defines the same firing periods
     */
    private boolean sameFiringPeriods(Message m1, Message m2) {
        List<DateInterval> d1 = m1.getParts().stream()
                .flatMap(p -> p.getEventDates().stream())
                .sorted()
                .collect(Collectors.toList());
        List<DateInterval> d2 = m2.getParts().stream()
                .flatMap(p -> p.getEventDates().stream())
                .sorted()
                .collect(Collectors.toList());

        if (d1.size() == d2.size()) {
            for (int x = 0; x < d1.size(); x++) {
                if (d1.get(x).compareTo(d2.get(x)) != 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * A firing period as the run reads it: its stored start, and its end as {@link #runPeriods} reads it
     */
    private record RunPeriod(Integer id, Integer areaId, Date from, Date to) implements Comparable<RunPeriod> {

        DateInterval toDateInterval() {
            DateInterval di = new DateInterval();
            di.setFromDate(from);
            di.setToDate(to);
            return di;
        }

        @Override
        public int compareTo(RunPeriod other) {
            int result = from.compareTo(other.from);
            return result != 0 ? result : to.compareTo(other.to);
        }
    }

    /**
     * Reads the stored firing periods for the run.
     * <p>
     * A period stored with its end at or before its start, made by the schedule editor before it was fixed ("09:00 -
     * 00:00", "22:00 - 02:00"), is read as ending one calendar day later in the target zone, which is what the officer
     * meant. The stored row is not changed. The periods so read are named in one WARN line per schedule and run. A
     * period that still ends before it starts after that is left out, with a WARN of its own.
     * <p>
     * A period is kept when its end, so read, is after the start of the window: one that ends exactly at it has no
     * time in the window and is left out.
     *
     * @param stored      the stored periods, read from one day before the window
     * @param zone        the target domain's zone
     * @param windowStart the start of the window
     * @return the periods of the run
     */
    private List<RunPeriod> runPeriods(List<FiringPeriod> stored, TimeZone zone, Date windowStart) {
        ZoneId zoneId = zone.toZoneId();
        List<RunPeriod> result = new ArrayList<>();
        List<String> readAsEndingTheNextDay = new ArrayList<>();
        for (FiringPeriod fp : stored) {
            Date from = fp.getFromDate();
            Date to = fp.getToDate();
            Integer areaId = fp.getArea().getId();
            boolean backward = !to.after(from);
            if (backward) {
                to = Date.from(ZonedDateTime.ofInstant(to.toInstant(), zoneId).plusDays(1).toInstant());
                if (!to.after(from)) {
                    log.warn("Firing period " + fp.getId() + " of area " + areaId + " ends ("
                            + fp.getToDate().toInstant() + ") more than a day before it starts (" + from.toInstant()
                            + "); it is left out of the firing exercises");
                    continue;
                }
            }
            if (!to.after(windowStart)) {
                continue;
            }
            if (backward) {
                readAsEndingTheNextDay.add(fp.getId() + " (area " + areaId + ", " + from.toInstant() + " to "
                        + fp.getToDate().toInstant() + ")");
            }
            result.add(new RunPeriod(fp.getId(), areaId, from, to));
        }
        if (!readAsEndingTheNextDay.isEmpty()) {
            log.warn("Firing periods stored with their end at or before their start, read as ending one day later: "
                    + String.join(", ", readAsEndingTheNextDay));
        }
        return result;
    }

    /**
     * The firing exercises generated for one schedule, the ids of the firing areas that got none because every
     * period of theirs in the window has ended, and the ids of those whose exercise could not be built from their
     * template
     */
    private record GeneratedExercises(List<Message> messages, Set<Integer> endedAreaIds, Set<Integer> failedAreaIds) {
    }

    /**
     * Based on the firing area message templates and the firing periods, generate new firing exercise messages.
     * <p>
     * An area whose periods in the window have all ended by {@code now} gets no exercise: it would be published with
     * a publication end in the past, expired by the minute sweep, and published again by the next run. Its id is
     * answered among the ended areas instead.
     * <p>
     * An area whose exercise cannot be built from its template (a template stored before the position check, with a
     * position off the globe, which the copy of its geometry refuses) gets no exercise and is counted among the failed
     * areas; its current exercise stays in force, and the schedule's other areas get theirs.
     */
    private GeneratedExercises generateFiringExerciseMessages(
            FiringSchedule schedule,
            List<Message> firingAreaMessages,
            List<RunPeriod> firingPeriods,
            Date now,
            RunCounts counts) {

        List<Message> result = new ArrayList<>();
        Set<Integer> endedAreaIds = new HashSet<>();
        Set<Integer> failedAreaIds = new HashSet<>();

        for (Message firingAreaMessage : firingAreaMessages) {

            Set<Integer> areaIds = firingAreaMessage.getAreas().stream()
                    .filter(a -> a.getType() == AreaType.FIRING_AREA)
                    .map(BaseEntity::getId)
                    .collect(Collectors.toSet());

            // Get firing periods for the message
            List<RunPeriod> fps = firingPeriods.stream()
                    .filter(fp -> areaIds.contains(fp.areaId()))
                    .sorted()
                    .collect(Collectors.toList());

            if (fps.isEmpty()) {
                continue;
            }

            // Periods that ended earlier today stay in an exercise that still has a period ahead. The ends are the
            // ends as the run reads them (runPeriods)
            boolean anyPeriodAhead = fps.stream().anyMatch(fp -> fp.to().after(now));
            if (!anyPeriodAhead) {
                endedAreaIds.add(getFiringAreaForMessage(firingAreaMessage).getId());
                continue;
            }

            try {
                result.add(buildFiringExerciseMessage(schedule, firingAreaMessage, fps, now));
            } catch (RuntimeException e) {
                Area firingArea = getFiringAreaForMessage(firingAreaMessage);
                failedAreaIds.add(firingArea.getId());
                counts.failedAreas++;
                log.error("Firing exercise of area " + firingArea.getDescs().get(0).getName()
                        + " not built from its template " + firingAreaMessage.getUid()
                        + "; the current one stays in force", e);
            }
        }

        return new GeneratedExercises(result, endedAreaIds, failedAreaIds);
    }

    /**
     * Builds the (un-persisted) firing exercise of one area from its template and its periods in the window, inside
     * the schedule's transaction. The copy of a part's geometry ({@code FeatureService.copyFeatureCollection}) runs
     * outside it, so its refusal of a position off the globe costs this area and does not mark the schedule's
     * transaction for rollback.
     */
    private Message buildFiringExerciseMessage(FiringSchedule schedule, Message firingAreaMessage,
                                               List<RunPeriod> fps, Date now) {
        // Construct the firing exercise message from the firing area message and firing periods
        Message message = new Message();
        message.assignNewUid();
        message.setMainType(schedule.getTargetMessageSeries().getMainType());
        message.setType(message.getMainType() == MainType.NW ? Type.LOCAL_WARNING : Type.TEMPORARY_NOTICE);
        message.setMessageSeries(schedule.getTargetMessageSeries());
        message.setStatus(Status.PUBLISHED);
        message.getAreas().addAll(firingAreaMessage.getAreas());
        message.getCategories().addAll(firingAreaMessage.getCategories());
        message.getCharts().addAll(firingAreaMessage.getCharts());

        // Copy message description entities and message parts. A template message without any description (no title,
        // no vicinity) holds no list of them at all: the exercise then has none to copy, and its title is made from
        // its areas and its parts' subjects below, as always
        MessageVo firingAreaMessageVo = firingAreaMessage.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_FILTER);
        if (firingAreaMessageVo.getDescs() != null) {
            firingAreaMessageVo.getDescs().forEach(d -> message.addDesc(new MessageDesc(d)));
        }
        firingAreaMessageVo.getParts()
                .forEach(p -> {
                    if (p.getGeometry() != null) {
                        p.setGeometry(featureService.copyFeatureCollection(p.getGeometry()));
                    }
                    // Remove any event date interval
                    p.setEventDates(null);
                    // Add the part to the new firing exercise message
                    message.addPart(new MessagePart(p));
                });

        // Get hold of the "time" message part, or add it as the very first message part
        if (message.partsByType(MessagePartType.TIME).isEmpty()) {
            MessagePart timePart = new MessagePart(MessagePartType.TIME);
            message.getParts().add(0, timePart);
            timePart.setMessage(message);
        }
        MessagePart timePart = message.partsByType(MessagePartType.TIME).get(0);

        // Copy firing periods to event dates
        fps.forEach(fp -> timePart.addEventDates(fp.toDateInterval()));

        // Make sure the message expires after last event date
        message.updateAggregateEventDateInterval();
        message.setPublishDateFrom(now);
        message.setPublishDateTo(message.getEventDateTo());

        // Format the firing periods as text
        formatTimeDescription(timePart, message.computeLanguages(), schedule.targetTimeZone());

        // Get hold of the "details" message part, or create it
        if (message.partsByType(MessagePartType.DETAILS).isEmpty()) {
            MessagePart detailsPart = message.addPart(new MessagePart(MessagePartType.DETAILS));
        }
        MessagePart detailsPart = message.partsByType(MessagePartType.DETAILS).get(0);

        // Format the message subject
        formatSubject(detailsPart, message.computeLanguages());

        // Update the message title
        message.setAutoTitle(true);
        message.updateMessageTitle();

        // NB: the order of the parts and of each part's event dates is their list position, which the
        // lists' @OrderColumn ("indexNo") stores; the elements' indexNo is read-only

        return message;
    }

    /**
     * Formats the date intervals as text
     * @param timePart the message part to update
     * @param languages the languages to include
     */
    private void formatTimeDescription(MessagePart timePart, Set<String> languages, TimeZone timeZone) {
        DictionaryEntry dateTimeFormat = dictionaryService.findByName("message")
                .getEntries().get("msg.time.date_time_format");
        DictionaryEntry timeFormat = dictionaryService.findByName("message")
                .getEntries().get("msg.time.time_format");

        languages.forEach(lang -> {
            String dtf = (dateTimeFormat != null && dateTimeFormat.getDesc(lang) != null)
                    ? dateTimeFormat.getDesc(lang).getValue()
                    : "d MMMM yyyy, 'hours' HH:mm";
            String tf = (timeFormat != null && timeFormat.getDesc(lang) != null)
                    ? timeFormat.getDesc(lang).getValue()
                    : "HH:mm";

            String txt = timePart.getEventDates().stream()
                    .map(di -> {
                        SimpleDateFormat sdf1 = new SimpleDateFormat(dtf, new Locale(lang));
                        SimpleDateFormat sdf2 = endsAtTheNextMidnight(di, timeZone)
                            ? new SimpleDateFormat(midnightAs24(tf))
                            : TimeUtils.sameDate(di.getFromDate(), di.getToDate(), timeZone)
                            ? new SimpleDateFormat(tf)
                            : sdf1;
                        sdf1.setTimeZone(timeZone);
                        sdf2.setTimeZone(timeZone);
                        return String.format("%s - %s", sdf1.format(di.getFromDate()), sdf2.format(di.getToDate()));
                    })
                    .collect(Collectors.joining(".<br>"));

            if (StringUtils.isNotBlank(txt)) {
                txt = txt + ".";
                timePart.checkCreateDesc(lang).setDetails(txt);
            }
        });
    }

    /**
     * Returns if the interval ends exactly at the local midnight that ends its start's day. Its end is then printed
     * as "24:00" on the start's date ("16 June 2026, hours 09:00 - 24:00"), not as 00:00 of the next day.
     */
    private static boolean endsAtTheNextMidnight(DateInterval di, TimeZone timeZone) {
        if (di.getFromDate() == null || di.getToDate() == null) {
            return false;
        }
        ZonedDateTime from = ZonedDateTime.ofInstant(di.getFromDate().toInstant(), timeZone.toZoneId());
        ZonedDateTime to = ZonedDateTime.ofInstant(di.getToDate().toInstant(), timeZone.toZoneId());
        return to.toLocalTime().equals(LocalTime.MIDNIGHT)
                && to.toLocalDate().equals(from.toLocalDate().plusDays(1));
    }

    /**
     * Returns the time pattern with each hour-of-day letter {@code H} (0-23) outside quoted text replaced by
     * {@code k} (1-24), which prints midnight as 24
     */
    static String midnightAs24(String timePattern) {
        StringBuilder result = new StringBuilder(timePattern.length());
        boolean quoted = false;
        for (char c : timePattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            result.append(!quoted && c == 'H' ? 'k' : c);
        }
        return result.toString();
    }

    /**
     * Formats the subject
     * @param detailsPart the message part to update
     * @param languages the languages to include
     */
    private void formatSubject(MessagePart detailsPart, Set<String> languages) {
        DictionaryEntry titleEntry = dictionaryService.findByName("message")
                .getEntries().get("msg.firing_exercises.subject");

        languages.forEach(lang -> {

            String subject = (titleEntry != null && titleEntry.getDesc(lang) != null)
                    ? titleEntry.getDesc(lang).getValue()
                    : "Firing exercises. Warning.";

            detailsPart.checkCreateDesc(lang).setSubject(subject);
        });
    }

    /**
     * Returns the firing area associated with the message
     * @param message the message
     * @return the firing area associated with the message
     */
    private Area getFiringAreaForMessage(Message message) {
        return message.getAreas().stream()
                .filter(a -> a.getType() == AreaType.FIRING_AREA)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the published firing area messages from the given domain.
     * <p>
     * A failed search throws, instead of answering "none" ({@code MessageSearchService.searchOrFail}): "none" cancels
     * the current exercises, and a failed search for them would issue duplicates.
     */
    private List<Message> getFiringAreaMessages(Domain domain, MessageSeries series, List<Area> firingAreas)
            throws Exception {

        Set<String> areaIds = firingAreas.stream().map(a -> a.getId().toString()).collect(Collectors.toSet());

        Set<String> seriesIds = new HashSet<>();
        if (domain != null) {
            seriesIds.addAll(domain.getMessageSeries().stream().map(MessageSeries::getSeriesId).collect(Collectors.toList()));
        }
        if (series != null) {
            seriesIds.add(series.getSeriesId());
        }

        MessageSearchParams params = new MessageSearchParams()
                .statuses(Status.PUBLISHED)
                .seriesIds(seriesIds)
                .areaIds(areaIds);

        // Ensure that the message is actually associated with a firing area
        return messageSearchService.searchOrFail(params).getData().stream()
                .filter(m -> m.getAreas().stream().anyMatch(a -> a.isActive() && a.getType() == AreaType.FIRING_AREA))
                .collect(Collectors.toList());
    }

    /** Returns the firing areas for the domain **/
    private List<Area> getFiringAreas(Domain domain) {
        AreaSearchParams param = new AreaSearchParams()
                .domain(domain == null ? null : domain.getDomainId())
                .inactive(false)
                .type(AreaType.FIRING_AREA);
        return areaService.searchAreas(param);
    }

}
