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
package org.niord.core.mailinglist;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.user.Contact;
import org.niord.model.message.Status;
import org.niord.test.ITTest;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MailingListService}, run against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>{@code createMailingList} / {@code updateMailingList} / {@code updateMailingListRecipients} /
 *       {@code deleteMailingList} - all of which are plain {@code @Transactional} (REQUIRES) and
 *       therefore JOIN the surrounding {@link TestTransaction}, so their writes are visible to
 *       in-tx queries and are rolled back at the end of each test;</li>
 *   <li>the duplicate-id / unknown-id {@link IllegalArgumentException} guards on create/update;</li>
 *   <li>the {@code checkComputeNextScheduledExecutions} side effect that fills in
 *       {@code nextScheduledExecution} for SCHEDULED triggers on create;</li>
 *   <li>{@code findByMailingListId} lookups against both created and the 5 seeded (inactive) lists;</li>
 *   <li>the {@code searchMailingLists} criteria query: id / name+lang / contact-email filters, the
 *       absence of any active filter (seeded inactive lists are still returned), the
 *       mailing-list-id ascending ordering, and the {@code maxSize} cap;</li>
 *   <li>the {@code findPendingScheduledTriggers} / {@code findStatusChangeTriggers} named queries,
 *       including their {@code mailingList.active = true} guard.</li>
 * </ul>
 *
 * <p><b>Oracles / non-obvious facts:</b>
 * <ul>
 *   <li>The seed DB contains 5 mailing lists, ALL inactive (active=0):
 *       audio-broadcast, navtex-baltico, navtex-rogaland, navwarn-overview, navwarn-update.
 *       {@code searchMailingLists} has no active filter, so an empty-params search returns at
 *       least those 5 rows.</li>
 *   <li>{@code MailingListTrigger.nextScheduledExecution} has no setter; this in-package test sets
 *       the field directly. For the "pending scheduled" tests the field is set to a multi-day
 *       PAST/FUTURE offset (not seconds) so the JVM-vs-DB timezone skew of the {@code :time = now()}
 *       comparison cannot flip the result.</li>
 *   <li>The {@code findPendingScheduledTriggers} / {@code findStatusChangeTriggers} named queries
 *       are plain queries that run in the caller (test) transaction, so rows persisted+flushed in
 *       the same test are visible to them.</li>
 * </ul>
 *
 * <p>Every test is wrapped in {@link TestTransaction}, so all writes roll back and the shared seed
 * stays pristine. All created mailing-list ids and contact emails are suffixed with
 * {@link System#nanoTime()} so seeded or sibling rows never interfere.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MailingListServiceITTest extends ITTest {

    @Autowired
    MailingListService mailingListService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Returns a unique mailing-list id so seeded/sibling rows never collide with test data. */
    private static String uniqueId() {
        return "IT-ML-" + System.nanoTime();
    }

    /**
     * Builds (in memory) a minimal valid {@link MailingList} with a single English desc (the
     * {@code name} field is {@code @NotNull}). Not persisted - pass to a service method or persist
     * explicitly.
     */
    private MailingList newList(String mailingListId, boolean active, String name) {
        MailingList ml = new MailingList();
        ml.setMailingListId(mailingListId);
        ml.setActive(active);
        MailingListDesc desc = ml.createDesc("en");
        desc.setName(name);
        return ml;
    }

    /** Adds a SCHEDULED trigger (with a @NotNull subject desc) to the list, returning the trigger. */
    private MailingListTrigger addScheduledTrigger(MailingList ml, ScheduleType scheduleType, String time) {
        return addScheduledTrigger(ml, scheduleType, time, null);
    }

    /** Adds a SCHEDULED trigger in the given time zone (null for none) to the list, returning the trigger. */
    private MailingListTrigger addScheduledTrigger(MailingList ml, ScheduleType scheduleType, String time, String zone) {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(scheduleType);
        t.setScheduledExecutionTime(time);
        t.setScheduledExecutionTimeZone(zone);
        MailingListTriggerDesc td = t.createDesc("en");
        td.setSubject("IT-subject-" + System.nanoTime());
        ml.addTrigger(t);
        return t;
    }

    /** Adds a STATUS_CHANGE trigger (with a @NotNull subject desc) to the list, returning it. */
    private MailingListTrigger addStatusChangeTrigger(MailingList ml, Status... statuses) {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.STATUS_CHANGE);
        t.setStatusChanges(Set.of(statuses));
        MailingListTriggerDesc td = t.createDesc("en");
        td.setSubject("IT-subject-" + System.nanoTime());
        ml.addTrigger(t);
        return t;
    }

    /** Persists+flushes a mailing list (and its cascaded descs/triggers) inside the test tx. */
    private MailingList persist(MailingList ml) {
        em.persist(ml);
        em.flush();
        return ml;
    }

    // ------------------------------------------------------------------------------------------- //
    // createMailingList
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMailingListPersistsNewListWithActiveFlagAndDesc() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Create Name"));

        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertNotNull(found, "createMailingList must persist a row retrievable by its id");
        assertEquals(id, found.getMailingListId());
        assertTrue(found.isActive(), "the active flag from the template must be persisted");
        assertEquals("IT Create Name", found.getDesc("en").getName(),
                "the English desc name from the template must be persisted");
    }

    @Test
    @Transactional
    void createMailingListWithDuplicateIdThrowsIllegalArgumentException() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, false, "IT Dup First"));
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mailingListService.createMailingList(newList(id, false, "IT Dup Second")));
        assertTrue(ex.getMessage().contains("already exists"),
                "creating a list with an existing mailingListId must be rejected");
    }

    @Test
    @Transactional
    void createMailingListComputesNextScheduledExecutionForScheduledTrigger() {
        String id = uniqueId();
        MailingList ml = newList(id, true, "IT Scheduled Name");
        addScheduledTrigger(ml, ScheduleType.DAILY, "15:55");

        // Precondition: the field is not yet computed before create.
        assertNull(ml.getTriggers().get(0).nextScheduledExecution,
                "precondition: nextScheduledExecution must be null before create");

        mailingListService.createMailingList(ml);
        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertEquals(1, found.getTriggers().size(), "the scheduled trigger must be persisted");
        assertNotNull(found.getTriggers().get(0).nextScheduledExecution,
                "createMailingList must run checkComputeNextScheduledExecutions, filling in "
                        + "nextScheduledExecution for a DAILY 15:55 scheduled trigger");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMailingList
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMailingListWithUnknownIdThrowsIllegalArgumentException() {
        MailingList template = newList(uniqueId(), true, "IT Update Unknown");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mailingListService.updateMailingList(template));
        assertTrue(ex.getMessage().contains("does not exists"),
                "updating a non-existing mailingListId must be rejected");
    }

    @Test
    @Transactional
    void updateMailingListUpdatesActiveFlagAndDescAndReplacesTriggers() {
        String id = uniqueId();

        // Create an inactive list with one STATUS_CHANGE trigger.
        MailingList created = newList(id, false, "IT Original Name");
        addStatusChangeTrigger(created, Status.PUBLISHED);
        mailingListService.createMailingList(created);
        em.flush();
        em.clear();

        // Build an update template: activate, rename, and replace with TWO scheduled triggers.
        MailingList template = newList(id, true, "IT Updated Name");
        addScheduledTrigger(template, ScheduleType.DAILY, "06:00");
        addScheduledTrigger(template, ScheduleType.MONDAY, "07:00");

        mailingListService.updateMailingList(template);
        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertTrue(found.isActive(), "updateMailingList must persist the new active flag");
        assertEquals("IT Updated Name", found.getDesc("en").getName(),
                "updateMailingList must copy the new desc name (copyDescsAndRemoveBlanks)");
        assertEquals(2, found.getTriggers().size(),
                "the original trigger set must be cleared and replaced by the two new triggers");
        assertTrue(found.getTriggers().stream().allMatch(t -> t.getType() == TriggerType.SCHEDULED),
                "all persisted triggers must be the replacement SCHEDULED triggers");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMailingList keeps a scheduled trigger's next run when its schedule did not change (MAILLIST8)
    // ------------------------------------------------------------------------------------------- //

    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    /**
     * Stores a list with one DAILY trigger at the given UTC time whose next execution is the given one, as the
     * scheduler left it, and returns the list's id. The value is set on the stored row, which the service never
     * computes, so a save that recomputed it would change it.
     */
    private String storedListWithOneTrigger(boolean active, String time, Date storedNext) {
        String id = uniqueId();
        MailingList ml = newList(id, active, "IT Keep Next Run");
        addScheduledTrigger(ml, ScheduleType.DAILY, time, "UTC").nextScheduledExecution = storedNext;
        persist(ml);
        em.clear();
        return id;
    }

    /** Saves the list again with the given scheduled triggers (schedule type, time, zone), as the editor does */
    private MailingList saveWithTriggers(String id, boolean active, Object[]... schedules) {
        MailingList template = newList(id, active, "IT Keep Next Run");
        for (Object[] s : schedules) {
            addScheduledTrigger(template, (ScheduleType) s[0], (String) s[1], (String) s[2]);
        }
        mailingListService.updateMailingList(template);
        em.flush();
        em.clear();
        return mailingListService.findByMailingListId(id);
    }

    private static Object[] schedule(ScheduleType type, String time, String zone) {
        return new Object[] { type, time, zone };
    }

    /**
     * Whether the next execution is the one a trigger of the given schedule gets when computed at the start or the
     * end of the save (compared as milliseconds: the stored value comes back as a java.sql.Timestamp)
     */
    private static boolean computedBetween(Date next, Date from, Date to, ScheduleType type, String time, String zone) {
        return next.getTime() == ScheduledExecutionTimeUtil.computeNextExecutionTime(from, type, time, zone).getTime()
                || next.getTime() == ScheduledExecutionTimeUtil.computeNextExecutionTime(to, type, time, zone).getTime();
    }

    /**
     * The fault's case: the list is saved unchanged in the minute of its daily trigger, after hh:mm:00 and before the
     * scheduler's run at hh:mm:37. The trigger was due and stays due, so the run that follows sends today's mail; a
     * save that recomputed it from now moved it to the next day.
     */
    @Test
    @Transactional
    void anUnchangedSaveInTheMinuteOfATrigger_keepsItDue() {
        ZonedDateTime minute = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        Date due = Date.from(minute.toInstant());
        String time = minute.format(DateTimeFormatter.ofPattern("HH:mm"));
        String id = storedListWithOneTrigger(true, time, due);

        MailingList found = saveWithTriggers(id, true, schedule(ScheduleType.DAILY, time, "UTC"));

        assertEquals(1, found.getTriggers().size());
        MailingListTrigger saved = found.getTriggers().get(0);
        assertEquals(due.getTime(), saved.nextScheduledExecution.getTime(),
                "the save keeps the trigger's stored next execution, " + due + ", rather than computing one from now");
        assertTrue(mailingListService.findPendingScheduledTriggers().stream()
                        .anyMatch(t -> t.getId().equals(saved.getId())),
                "the trigger is still due after the save, so the scheduler's next run executes it");
    }

    /** A next execution a failed run postponed (by at most an hour, A16) is kept through a save too */
    @Test
    @Transactional
    void anUnchangedSaveKeepsAPostponedNextRun() {
        Date postponed = new Date(System.currentTimeMillis() + 40 * MINUTE);
        String id = storedListWithOneTrigger(true, "06:00", postponed);

        MailingList found = saveWithTriggers(id, true, schedule(ScheduleType.DAILY, "06:00", "UTC"));

        assertEquals(postponed.getTime(), found.getTriggers().get(0).nextScheduledExecution.getTime(),
                "the save keeps the postponed next execution rather than moving it to the regular time");
    }

    /**
     * A trigger whose time, schedule type or time zone the save changes takes no stored next execution: each is
     * computed from now. White space around the time does not count as a change.
     */
    @Test
    @Transactional
    void aTriggerWhoseScheduleChanged_isComputedFromNow() {
        Date stored = new Date(System.currentTimeMillis() - 2 * DAY);
        String id = storedListWithOneTrigger(true, "06:00", stored);

        Date before = new Date();
        MailingList found = saveWithTriggers(id, true,
                schedule(ScheduleType.DAILY, "07:15", "UTC"),
                schedule(ScheduleType.MONDAY, "06:00", "UTC"),
                schedule(ScheduleType.DAILY, "06:00", "Europe/Copenhagen"));
        Date after = new Date();

        assertEquals(3, found.getTriggers().size());
        for (MailingListTrigger t : found.getTriggers()) {
            assertNotEquals(stored.getTime(), t.nextScheduledExecution.getTime(),
                    "a changed schedule does not keep the stored run");
            assertTrue(computedBetween(t.nextScheduledExecution, before, after, t.getScheduleType(),
                            t.getScheduledExecutionTime(), t.getScheduledExecutionTimeZone()),
                    t.getScheduleType() + " " + t.getScheduledExecutionTime() + " " + t.getScheduledExecutionTimeZone()
                            + " is computed from now: " + t.nextScheduledExecution);
        }

        Date kept = new Date(System.currentTimeMillis() + 40 * MINUTE);
        String spaced = storedListWithOneTrigger(true, "06:00", kept);
        MailingList same = saveWithTriggers(spaced, true, schedule(ScheduleType.DAILY, " 06:00 ", "UTC"));
        assertEquals(kept.getTime(), same.getTriggers().get(0).nextScheduledExecution.getTime(),
                "white space around the time is not a change of schedule");
    }

    /**
     * Two stored triggers of one schedule keep their two next executions, each taken once; a third trigger of the
     * same schedule, new in the save, is computed from now.
     */
    @Test
    @Transactional
    void triggersOfOneSchedule_eachTakeOneStoredRun_andANewOneIsComputed() {
        Date due = new Date(System.currentTimeMillis() - 2 * DAY);
        Date postponed = new Date(System.currentTimeMillis() + 40 * MINUTE);
        String id = uniqueId();
        MailingList ml = newList(id, true, "IT Keep Next Run");
        addScheduledTrigger(ml, ScheduleType.DAILY, "06:00", "UTC").nextScheduledExecution = due;
        addScheduledTrigger(ml, ScheduleType.DAILY, "06:00", "UTC").nextScheduledExecution = postponed;
        persist(ml);
        em.clear();

        Date before = new Date();
        MailingList found = saveWithTriggers(id, true,
                schedule(ScheduleType.DAILY, "06:00", "UTC"),
                schedule(ScheduleType.DAILY, "06:00", "UTC"),
                schedule(ScheduleType.DAILY, "06:00", "UTC"));
        Date after = new Date();

        List<Long> nexts = found.getTriggers().stream().map(t -> t.nextScheduledExecution.getTime()).sorted().toList();
        assertEquals(3, nexts.size());
        assertEquals(due.getTime(), nexts.get(0), "one trigger keeps the due run");
        List<Long> rest = nexts.subList(1, 3);
        assertTrue(rest.contains(postponed.getTime()), "one trigger keeps the postponed run: " + nexts);
        long third = rest.get(0) == postponed.getTime() ? rest.get(1) : rest.get(0);
        assertTrue(computedBetween(new Date(third), before, after, ScheduleType.DAILY, "06:00", "UTC"),
                "the third trigger has no stored run left to take and is computed from now: " + nexts);
    }

    /**
     * A list that was inactive before the save keeps none of its stored next executions: the scheduler did not run
     * them, so they may lie far in the past, and a save that activates the list computes them from now, as before.
     */
    @Test
    @Transactional
    void aSaveOfAListThatWasInactive_computesItsTriggersFromNow() {
        Date stale = new Date(System.currentTimeMillis() - 2 * DAY);
        String id = storedListWithOneTrigger(false, "06:00", stale);

        Date before = new Date();
        MailingList found = saveWithTriggers(id, true, schedule(ScheduleType.DAILY, "06:00", "UTC"));
        Date after = new Date();

        Date next = found.getTriggers().get(0).nextScheduledExecution;
        assertTrue(computedBetween(next, before, after, ScheduleType.DAILY, "06:00", "UTC"),
                "the activated list's trigger is computed from now, not left at " + stale + ": " + next);
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMailingListRecipients
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMailingListRecipientsWithUnknownIdThrowsIllegalArgumentException() {
        MailingList template = newList(uniqueId(), true, "IT Recipients Unknown");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mailingListService.updateMailingListRecipients(template));
        assertTrue(ex.getMessage().contains("does not exists"),
                "updating recipients of a non-existing mailingListId must be rejected");
    }

    @Test
    @Transactional
    void updateMailingListRecipientsPersistsResolvedContacts() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Recipients Name"));
        em.flush();

        // Persist a Contact so persistedList(Contact.class, ...) can resolve it by id.
        String email = "it-recipient-" + System.nanoTime() + "@example.com";
        Contact contact = new Contact();
        contact.setEmail(email);
        contact.setName("IT Recipient");
        em.persist(contact);
        em.flush();

        // Build a template that references the persisted contact (by id) and no users.
        MailingList template = newList(id, true, "IT Recipients Name");
        template.setUsers(List.of());
        template.setContacts(List.of(contact));

        mailingListService.updateMailingListRecipients(template);
        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertEquals(0, found.getUsers().size(), "the user list must be replaced with an empty list");
        assertEquals(1, found.getContacts().size(),
                "updateMailingListRecipients must persist exactly the one resolved contact");
        assertEquals(email, found.getContacts().get(0).getEmail(),
                "the persisted contact must be the one referenced by the template");
    }

    @Test
    @Transactional
    void updateMailingListRecipientsDropsContactWithUnknownId() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Recipients Unknown"));
        em.flush();

        // persistedList maps each contact via getByPrimaryKey(Contact, id); a non-null but unknown id
        // resolves to null and is filtered out (the .filter(nonNull) applies to the lookup RESULTS).
        Contact unknown = new Contact();
        unknown.setId(Integer.MAX_VALUE);
        unknown.setEmail("it-unknown-" + System.nanoTime() + "@example.com");
        unknown.setName("IT Unknown");

        MailingList template = newList(id, true, "IT Recipients Unknown");
        template.setContacts(List.of(unknown));

        mailingListService.updateMailingListRecipients(template);
        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertEquals(0, found.getContacts().size(),
                "a contact whose id does not resolve to a persisted row is dropped");
    }

    @Test
    @Transactional
    void updateMailingListRecipientsSkipsTransientContact() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Recipients Transient"));
        em.flush();

        // A brand-new Contact has a null id; persistedList now filters out null-id entities before the
        // getByPrimaryKey lookup, so a transient contact is dropped exactly like an unknown-id one (ML2).
        Contact transientContact = new Contact();
        transientContact.setEmail("it-transient-" + System.nanoTime() + "@example.com");
        transientContact.setName("IT Transient");

        MailingList template = newList(id, true, "IT Recipients Transient");
        template.setContacts(List.of(transientContact));

        mailingListService.updateMailingListRecipients(template);
        em.flush();
        em.clear();

        MailingList found = mailingListService.findByMailingListId(id);
        assertEquals(0, found.getContacts().size(),
                "a transient (null-id) contact is skipped like an unknown-id one");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteMailingList
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteMailingListRemovesExistingListAndReturnsTrue() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Delete Name"));
        em.flush();
        em.clear();

        boolean deleted = mailingListService.deleteMailingList(id);
        assertTrue(deleted, "deleteMailingList must return true for an existing list");

        em.flush();
        em.clear();

        assertNull(mailingListService.findByMailingListId(id),
                "the deleted list must no longer be retrievable");
    }

    @Test
    @Transactional
    void deleteMailingListReturnsFalseForUnknownId() {
        boolean deleted = mailingListService.deleteMailingList("IT-ML-unknown-" + System.nanoTime());
        assertFalse(deleted, "deleteMailingList must return false for a non-existing id");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByMailingListId
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByMailingListIdReturnsSeededInactiveAudioBroadcast() {
        MailingList found = mailingListService.findByMailingListId("audio-broadcast");
        assertNotNull(found, "the seeded 'audio-broadcast' mailing list must be present");
        assertEquals("audio-broadcast", found.getMailingListId());
        assertFalse(found.isActive(),
                "all 5 seeded mailing lists are inactive (active=0) in the seed database");
    }

    @Test
    @Transactional
    void findByMailingListIdReturnsNullForUnknownId() {
        assertNull(mailingListService.findByMailingListId("IT-ML-no-such-id-" + System.nanoTime()),
                "findByMailingListId must return null for an unknown id");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchMailingLists
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByMailingListIdReturnsExactlyThatList() {
        String id = uniqueId();
        mailingListService.createMailingList(newList(id, true, "IT Search By Id"));
        em.flush();

        List<MailingList> result = mailingListService.searchMailingLists(
                new MailingListSearchParams().mailingListId(id));

        assertEquals(1, result.size(), "a unique mailingListId must match exactly one list");
        assertEquals(id, result.get(0).getMailingListId());
    }

    @Test
    @Transactional
    void searchByNameAndLanguageReturnsTheCreatedList() {
        String id = uniqueId();
        String name = "IT-SearchName-" + System.nanoTime();
        mailingListService.createMailingList(newList(id, true, name));
        em.flush();

        List<MailingList> result = mailingListService.searchMailingLists(
                new MailingListSearchParams().name(name).language("en"));

        assertEquals(1, result.size(),
                "a unique desc name + lang 'en' must match exactly the created list");
        assertEquals(id, result.get(0).getMailingListId());
    }

    @Test
    @Transactional
    void searchByNameWithWrongLanguageReturnsNoResults() {
        String id = uniqueId();
        String name = "IT-SearchNameLang-" + System.nanoTime();
        mailingListService.createMailingList(newList(id, true, name));
        em.flush();

        // The desc is in 'en'; searching the same name with lang 'da' must not match.
        List<MailingList> result = mailingListService.searchMailingLists(
                new MailingListSearchParams().name(name).language("da"));

        assertTrue(result.isEmpty(),
                "name search adds a lang-equals predicate, so a wrong language yields no results");
    }

    @Test
    @Transactional
    void searchByContactEmailReturnsTheListHoldingThatContact() {
        String id = uniqueId();
        String email = "it-search-contact-" + System.nanoTime() + "@example.com";

        // Persist a contact and attach it to a created list.
        Contact contact = new Contact();
        contact.setEmail(email);
        contact.setName("IT Search Contact");
        em.persist(contact);
        em.flush();

        MailingList ml = newList(id, true, "IT Search By Contact");
        ml.setContacts(List.of(contact));
        persist(ml);

        List<MailingList> result = mailingListService.searchMailingLists(
                new MailingListSearchParams().contactEmail(email));

        assertEquals(1, result.size(), "a unique contact email must match exactly one list");
        assertEquals(id, result.get(0).getMailingListId());
    }

    @Test
    @Transactional
    void searchWithEmptyParamsReturnsSeededListsOrderedByMailingListIdAscending() {
        List<MailingList> result = mailingListService.searchMailingLists(new MailingListSearchParams());

        assertTrue(result.size() >= 5,
                "searchMailingLists has no active filter, so the 5 inactive seeded lists are returned");

        List<String> ids = result.stream()
                .map(MailingList::getMailingListId)
                .collect(Collectors.toList());
        List<String> sorted = ids.stream().sorted().collect(Collectors.toList());
        assertEquals(sorted, ids, "results must be ordered by mailingListId ascending");

        assertTrue(ids.contains("audio-broadcast") && ids.contains("navwarn-update"),
                "the seeded (inactive) lists must be included despite their inactive flag");
    }

    @Test
    @Transactional
    void searchHonoursMaxSizeCap() {
        List<MailingList> result = mailingListService.searchMailingLists(
                (MailingListSearchParams) new MailingListSearchParams().maxSize(1));

        assertTrue(result.size() <= 1, "maxSize(1) must cap the result set at one row");
    }

    // ------------------------------------------------------------------------------------------- //
    // findPendingScheduledTriggers
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findPendingScheduledTriggersReturnsPastTriggerOnActiveListOnly() {
        Date twoDaysAgo = new Date(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000);

        // Active list with a SCHEDULED trigger whose nextScheduledExecution is in the past -> pending.
        MailingList activeList = newList(uniqueId(), true, "IT Pending Active");
        MailingListTrigger activeTrigger = addScheduledTrigger(activeList, ScheduleType.DAILY, "15:55");
        activeTrigger.nextScheduledExecution = twoDaysAgo;
        persist(activeList);
        Integer activeTriggerId = activeTrigger.getId();

        // Inactive list with an identical past trigger -> excluded by the active = true guard.
        MailingList inactiveList = newList(uniqueId(), false, "IT Pending Inactive");
        MailingListTrigger inactiveTrigger = addScheduledTrigger(inactiveList, ScheduleType.DAILY, "15:55");
        inactiveTrigger.nextScheduledExecution = twoDaysAgo;
        persist(inactiveList);
        Integer inactiveTriggerId = inactiveTrigger.getId();

        em.flush();

        List<Integer> pendingIds = mailingListService.findPendingScheduledTriggers().stream()
                .map(MailingListTrigger::getId)
                .collect(Collectors.toList());

        assertTrue(pendingIds.contains(activeTriggerId),
                "a SCHEDULED trigger with a past nextScheduledExecution on an ACTIVE list must be pending");
        assertFalse(pendingIds.contains(inactiveTriggerId),
                "the same past trigger on an INACTIVE list must NOT be pending (active = true guard)");
    }

    @Test
    @Transactional
    void findPendingScheduledTriggersExcludesFutureTriggerOnActiveList() {
        Date twoDaysAhead = new Date(System.currentTimeMillis() + 2L * 24 * 60 * 60 * 1000);

        MailingList activeList = newList(uniqueId(), true, "IT Pending Future");
        MailingListTrigger futureTrigger = addScheduledTrigger(activeList, ScheduleType.DAILY, "15:55");
        futureTrigger.nextScheduledExecution = twoDaysAhead;
        persist(activeList);
        Integer futureTriggerId = futureTrigger.getId();

        em.flush();

        List<Integer> pendingIds = mailingListService.findPendingScheduledTriggers().stream()
                .map(MailingListTrigger::getId)
                .collect(Collectors.toList());

        assertFalse(pendingIds.contains(futureTriggerId),
                "a SCHEDULED trigger whose nextScheduledExecution is in the future must NOT be pending");
    }

    // ------------------------------------------------------------------------------------------- //
    // findStatusChangeTriggers
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findStatusChangeTriggersMatchesTriggerStatusOnActiveList() {
        MailingList activeList = newList(uniqueId(), true, "IT Status Active");
        MailingListTrigger trigger = addStatusChangeTrigger(activeList, Status.PUBLISHED);
        persist(activeList);
        Integer triggerId = trigger.getId();

        em.flush();

        List<Integer> publishedIds = mailingListService.findStatusChangeTriggers(Status.PUBLISHED).stream()
                .map(MailingListTrigger::getId)
                .collect(Collectors.toList());
        assertTrue(publishedIds.contains(triggerId),
                "a STATUS_CHANGE trigger whose statusChanges contains PUBLISHED must match PUBLISHED");

        List<Integer> verifiedIds = mailingListService.findStatusChangeTriggers(Status.VERIFIED).stream()
                .map(MailingListTrigger::getId)
                .collect(Collectors.toList());
        assertFalse(verifiedIds.contains(triggerId),
                "the same trigger must NOT match a status (VERIFIED) it does not subscribe to");
    }

    @Test
    @Transactional
    void findStatusChangeTriggersExcludesTriggerOnInactiveList() {
        MailingList inactiveList = newList(uniqueId(), false, "IT Status Inactive");
        MailingListTrigger trigger = addStatusChangeTrigger(inactiveList, Status.PUBLISHED);
        persist(inactiveList);
        Integer triggerId = trigger.getId();

        em.flush();

        List<Integer> publishedIds = mailingListService.findStatusChangeTriggers(Status.PUBLISHED).stream()
                .map(MailingListTrigger::getId)
                .collect(Collectors.toList());

        assertFalse(publishedIds.contains(triggerId),
                "a STATUS_CHANGE trigger on an INACTIVE list must NOT be returned (active = true guard)");
    }
}
