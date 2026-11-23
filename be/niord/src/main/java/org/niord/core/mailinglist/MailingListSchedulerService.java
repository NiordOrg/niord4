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

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.niord.core.model.BaseEntity;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The sole purpose of this class is to periodically check and execute pending scheduled mailing list triggers.
 * <p>
 * The code is executed in a separate class (rather than from the {@code MailingListExecutionService})
 * to better control transaction handling.<br>
 * With this model, if one mailing list execution fails, it will not roll back a single transaction
 * for all mailing lists. A trigger whose run fails is postponed by at most an hour, rather than run again every
 * minute, and its failure is recorded in the Mail Queue as a mail in status Error, at most once a day while it keeps
 * failing. A trigger whose free-text search was answered while the index was being filled after the start, or after a
 * change of its settings ({@link IncompleteIndexException}), is not a failure: it is postponed by a few minutes at a
 * time, never past the end of the half hour after the later of the two, logged as one warning and recorded apart from
 * the failures, at most once a day.
 */
// PORT: was @jakarta.ejb.Stateless; now a Spring @Component (singleton).
@Service
@SuppressWarnings("unused")
public class MailingListSchedulerService extends BaseService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MailingListSchedulerService.class);

    private final MailingListService mailingListService;

    private final MailingListExecutionService mailingListExecutionService;

    /**
     * The scheduled triggers whose wait for the free-text index this backend has logged as a warning since its start:
     * a waiting trigger is tried again every few minutes, and only its first wait is a WARN
     */
    private final Set<Integer> indexWaitsLogged = ConcurrentHashMap.newKeySet();

    MailingListSchedulerService(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService) {
        this.mailingListService = mailingListService;
        this.mailingListExecutionService = mailingListExecutionService;
    }

    /**
     * Checks every minute if there are pending scheduled mailing list triggers.
     * <p>
     * The schedule is the property {@code niord.schedule.mailing-list-trigger-cron}, with the shipped value as its
     * default; {@code -} ({@link Scheduled#CRON_DISABLED}) switches the job off, so that a copy of another
     * deployment's data can be started without its due triggers writing mails and moving on to their next time.
     */
    @Scheduled(cron = "${niord.schedule.mailing-list-trigger-cron:37 */1 */1 * * ?}")
    void checkExecuteScheduledTriggers() {

        long t0 = System.currentTimeMillis();

        List<Integer> pendingTriggers = mailingListService.findPendingScheduledTriggers().stream()
                .map(BaseEntity::getId)
                .collect(Collectors.toList());

        if (!pendingTriggers.isEmpty()) {
            log.debug("Processing " + pendingTriggers.size() + " scheduled triggers");
            for (Integer triggerId : pendingTriggers) {
                try {
                    // In a new transaction: the mails and the trigger's next execution commit together or not at all
                    mailingListExecutionService.executeScheduledTrigger(triggerId);
                } catch (IncompleteIndexException wait) {
                    // Not a fault: the free-text index is being filled after the start or a change of its settings.
                    // Postponed by a few minutes
                    // (never past the end of the wait), so the mail follows soon after the index is complete
                    String when = postponeFailedTrigger(triggerId, wait.getRetryAt());
                    if (indexWaitsLogged.add(triggerId)) {
                        log.warn("Scheduled mailing list trigger " + triggerId + " waits for the free-text index, "
                                + "which is being built again after the start or a change of its settings; it is tried "
                                + "again " + when
                                + ", and its mail is made once the index is complete, by "
                                + wait.getWaitUntil().toInstant() + " at the latest");
                    } else {
                        log.debug("Scheduled mailing list trigger " + triggerId + " still waits for the free-text "
                                + "index; it is tried again " + when);
                    }
                    // Its own record, apart from the failures': it says the mail follows, and does not hold back
                    // the record of a run that fails for a real cause later the same day
                    try {
                        mailingListExecutionService.recordScheduledTriggerWait(triggerId, wait);
                    } catch (Exception recordFailure) {
                        log.error("Failed recording the wait of scheduled mailing list trigger " + triggerId,
                                recordFailure);
                    }
                } catch (Exception e) {
                    // Per trigger, so one bad trigger does not stop the rest of the run. The failed trigger is
                    // postponed, so it is not run again every minute; the ERROR line carries the trace, because
                    // the cause (an invalid stored time, a stale publication= id in the stored query, a template
                    // error) is in the exception rather than in this message.
                    log.error("Scheduled mailing list trigger " + triggerId + " failed; it is tried again "
                            + postponeFailedTrigger(triggerId, null), e);
                    // Leave a record in the Mail Queue, so the mail that was due does not vanish without a trace.
                    // After the postponement, so a failure to record cannot bring back the every-minute retry.
                    try {
                        mailingListExecutionService.recordScheduledTriggerFailure(triggerId, e);
                    } catch (Exception recordFailure) {
                        log.error("Failed recording the failure of scheduled mailing list trigger " + triggerId,
                                recordFailure);
                    }
                }
            }
            log.debug(String.format("Executed %d scheduled mailing list triggers in %d ms",
                    pendingTriggers.size(),
                    System.currentTimeMillis() - t0));
        }
    }

    /**
     * Postpones a failed trigger, to no later than the given time when there is one, and says when it is tried
     * again: "at" the time set, or "in a minute" when the postponement itself failed (its failure is logged) and the
     * trigger stays due
     */
    private String postponeFailedTrigger(Integer triggerId, Date notAfter) {
        try {
            Date next = notAfter == null
                    ? mailingListExecutionService.postponeFailedScheduledTrigger(triggerId)
                    : mailingListExecutionService.postponeFailedScheduledTrigger(triggerId, notAfter);
            return next == null ? "never: it is no longer a scheduled trigger" : "at " + next.toInstant();
        } catch (Exception e) {
            log.error("Failed postponing scheduled mailing list trigger " + triggerId + " after its run failed", e);
            return "in a minute";
        }
    }
}
