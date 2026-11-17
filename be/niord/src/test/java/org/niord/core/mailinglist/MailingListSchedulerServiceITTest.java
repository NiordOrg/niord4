/*
 * Copyright 2017 Danish Maritime Authority.
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
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MailingListSchedulerService#checkExecuteScheduledTriggers()} — the
 * {@code @Scheduled} job (it fires once a minute) that finds every pending SCHEDULED mailing-list trigger
 * (type {@code SCHEDULED}, on an <em>active</em> mailing list, whose {@code nextScheduledExecution} is in the
 * past) and, for each, recomputes its next execution and executes it. Both happen in one {@code REQUIRES_NEW}
 * transaction per trigger, which COMMITS, so the fixtures are committed (not in a
 * {@code @Transactional}) and cleaned up best-effort in {@link #afterEach()}.
 *
 * <p>The observable effect asserted here is the <b>reschedule</b>: a due trigger's {@code nextScheduledExecution}
 * is advanced from the past into the future after the scheduler runs (the trigger has no recipients/template, so
 * {@code executeScheduledTrigger} produces no mail and never throws, and the new time commits). Conversely a trigger
 * on an <em>inactive</em> mailing list is skipped, so its due time is unchanged. A trigger whose run fails is
 * postponed by at most an hour: {@code MailingListDeliveryFaultITTest} (MAILLIST3, MAILLIST6).
 *
 * <p>The package-private scheduled method is reachable from this same-package test. Only the cron firing
 * <em>time</em> itself is not asserted (the Quarkus scheduler is not driven). All seeded mailing lists are
 * inactive, so the seeded SCHEDULED trigger is never pending and is never touched by this test. This is a
 * framework-coupled test — see docs/untestable-code.md and docs/framework-coupled-tests.md.
 */
class MailingListSchedulerServiceITTest extends ITTest {

    @Autowired
    MailingListSchedulerService schedulerService;

    @PersistenceContext
    EntityManager em;

    /** Mailing-list ids COMMITTED outside the test transaction; removed best-effort in {@link #afterEach()}. */
    private final List<Integer> committedMailingListIds = new ArrayList<>();

    @AfterEach
    void afterEach() {
        for (Integer id : committedMailingListIds) {
            try {
                Tx.requiringNew().run(() -> {
                    MailingList ml = em.find(MailingList.class, id);
                    if (ml != null) {
                        em.remove(ml); // cascades the trigger removal
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        committedMailingListIds.clear();
    }

    /**
     * Commits a mailing list (active or not) carrying one SCHEDULED trigger whose nextScheduledExecution is
     * forced two days into the past, making it a due/pending scheduled trigger. Returns the committed trigger id.
     * nextScheduledExecution has no public setter (it is computed), so it is forced past with a bulk JPQL update.
     */
    private int commitDueScheduledTrigger(boolean active) {
        int[] triggerIdHolder = new int[1];
        Integer mlId = Tx.requiringNew().call(() -> {
            MailingList ml = new MailingList();
            ml.setMailingListId("IT-MLSCHED-" + System.nanoTime());
            ml.setActive(active);
            ml.createDesc("en").setName("Scheduler IT");

            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.SCHEDULED);
            trigger.setScheduleType(ScheduleType.DAILY);
            trigger.setScheduledExecutionTime("12:00");
            ml.addTrigger(trigger);

            em.persist(ml);
            em.flush();

            em.createQuery("update MailingListTrigger t set t.nextScheduledExecution = :past where t.id = :id")
                    .setParameter("past", new Date(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000L))
                    .setParameter("id", trigger.getId())
                    .executeUpdate();

            triggerIdHolder[0] = trigger.getId();
            return ml.getId();
        });
        committedMailingListIds.add(mlId);
        return triggerIdHolder[0];
    }

    /** Reads a trigger's nextScheduledExecution from a fresh (committed) transaction. */
    private Date nextScheduledExecutionOf(int triggerId) {
        return Tx.requiringNew().call(() ->
                em.find(MailingListTrigger.class, triggerId).getNextScheduledExecution());
    }

    @Test
    void checkExecuteScheduledTriggersExecutesADueTriggerAndReschedulesItIntoTheFuture() {
        int triggerId = commitDueScheduledTrigger(true);
        // Capture the reference instant ONCE, before the scheduler runs. The trigger starts ~2 days in
        // the past and is rescheduled to a future daily occurrence, so both the before/after assertions
        // compare against this single fixed instant rather than re-reading the clock at assertion time.
        Date now = new Date();
        assertTrue(nextScheduledExecutionOf(triggerId).before(now),
                "precondition: the trigger on an active list must start out due (nextScheduledExecution in the past)");

        schedulerService.checkExecuteScheduledTriggers();

        assertTrue(nextScheduledExecutionOf(triggerId).after(now),
                "checkExecuteScheduledTriggers must execute the due trigger and recompute its next execution "
                        + "into the future");
    }

    @Test
    void checkExecuteScheduledTriggersSkipsTriggersOnInactiveMailingLists() {
        int triggerId = commitDueScheduledTrigger(false); // inactive list -> never pending
        Date now = new Date();
        // Snapshot the (past) due time before the scheduler runs so we can assert it is left EXACTLY as
        // it was — a stronger check than "still in the past", and one that does not re-read the clock.
        Date dueTimeBefore = nextScheduledExecutionOf(triggerId);
        assertTrue(dueTimeBefore.before(now),
                "precondition: the inactive list's trigger is due (nextScheduledExecution in the past)");

        schedulerService.checkExecuteScheduledTriggers();

        assertEquals(dueTimeBefore, nextScheduledExecutionOf(triggerId),
                "a trigger on an inactive mailing list must NOT be executed or rescheduled, "
                        + "so its (past) nextScheduledExecution is unchanged");
    }
}
