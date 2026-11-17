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
import java.util.stream.Collectors;

/**
 * The sole purpose of this class is to periodically check and execute pending scheduled mailing list triggers.
 * <p>
 * The code is executed in a separate class (rather than from the {@code MailingListExecutionService})
 * to better control transaction handling.<br>
 * With this model, if one mailing list execution fails, it will not roll back a single transaction
 * for all mailing lists. A trigger whose run fails is postponed by at most an hour, rather than run again every
 * minute.
 */
// PORT: was @jakarta.ejb.Stateless; now a Spring @Component (singleton).
@Service
@SuppressWarnings("unused")
public class MailingListSchedulerService extends BaseService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MailingListSchedulerService.class);

    private final MailingListService mailingListService;

    private final MailingListExecutionService mailingListExecutionService;

    MailingListSchedulerService(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService) {
        this.mailingListService = mailingListService;
        this.mailingListExecutionService = mailingListExecutionService;
    }

    /**
     * Checks every minute if there are pending scheduled mailing list triggers.
     */
    @Scheduled(cron="37 */1 */1 * * ?")
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
                } catch (Exception e) {
                    // Per trigger, so one bad trigger does not stop the rest of the run. The failed trigger is
                    // postponed, so it is not run again every minute; the ERROR line carries the trace, because
                    // the cause (an invalid stored time, a stale publication= id in the stored query, a template
                    // error) is in the exception rather than in this message.
                    log.error("Scheduled mailing list trigger " + triggerId + " failed; it is tried again "
                            + postponeFailedTrigger(triggerId), e);
                }
            }
            log.debug(String.format("Executed %d scheduled mailing list triggers in %d ms",
                    pendingTriggers.size(),
                    System.currentTimeMillis() - t0));
        }
    }

    /**
     * Postpones a failed trigger, and says when it is tried again: "at" the time set, or "in a minute" when the
     * postponement itself failed (its failure is logged) and the trigger stays due
     */
    private String postponeFailedTrigger(Integer triggerId) {
        try {
            Date next = mailingListExecutionService.postponeFailedScheduledTrigger(triggerId);
            return next == null ? "never: it is no longer a scheduled trigger" : "at " + next.toInstant();
        } catch (Exception e) {
            log.error("Failed postponing scheduled mailing list trigger " + triggerId + " after its run failed", e);
            return "in a minute";
        }
    }
}
