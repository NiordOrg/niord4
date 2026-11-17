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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the dispatch logic of the message-status topic flow handled by
 * {@link MailingListMessageListener#checkStatusChangeMailingListExecution(String, Status)} — the method
 * the listener's JMS consumer loop invokes for every received status update.
 *
 * <p>They pin the contract: the listener looks up the status-change triggers for the broadcast status,
 * executes each of them for the broadcast message UID, and — crucially — keeps going when an individual
 * trigger execution fails (the per-trigger error is logged and recorded in the Mail Queue, so one bad trigger cannot
 * block the rest).
 *
 * <p>No broker, database or Quarkus container is involved: the listener is exercised as a plain object
 * with hand-written recording {@link MailingListService} / {@link MailingListExecutionService} doubles
 * (the project uses no mocking framework). The surrounding {@code onMessage(...)} method and the live
 * consumer loop are out of scope here (see docs/untestable-code.md); the real broker delivery is covered
 * by {@code MessageStatusTopicITTest} / {@code MessageStatusPublishITTest}.
 */
public class MailingListMessageListenerTest {

    private RecordingMailingListService mailingListService;
    private RecordingMailingListExecutionService executionService;
    private MailingListMessageListener listener;

    @BeforeEach
    public void setUp() {
        mailingListService = new RecordingMailingListService();
        executionService = new RecordingMailingListExecutionService();
        // PORT: the listener's collaborators are now `private final` and wired via a package-private
        // constructor (constructor injection). We pass the recording doubles as the matching arguments
        // and `null` for the ConnectionFactory the test never set (the old @Inject field was simply
        // null, and the JMS consumer loop that uses it is out of scope here — see class javadoc).
        listener = new MailingListMessageListener(mailingListService, executionService, null);
        // PORT: the listener's `log` is now `private static final` (Spring port) and can no longer
        // be replaced with a test logger. The per-trigger error is swallowed and only logged, so the
        // logger has no observable effect on the dispatch contract under test; we let the real logger
        // run. The "keep going after a failing trigger" behaviour is still asserted directly via
        // attemptedTriggerIds vs executedTriggerIds in aFailingTriggerDoesNotPreventTheOthersFromRunning.
    }

    @Test
    public void executesEachStatusChangeTriggerForTheMessageUid() {
        mailingListService.triggers = triggers(10, 20, 30);

        listener.checkStatusChangeMailingListExecution("uid-1", Status.PUBLISHED);

        assertEquals(Status.PUBLISHED, mailingListService.queriedStatus,
                "the lookup must use the broadcast status");
        assertEquals(Arrays.asList(10, 20, 30), executionService.executedTriggerIds,
                "every status-change trigger must be executed once");
        assertEquals(Arrays.asList("uid-1", "uid-1", "uid-1"), executionService.executedUids,
                "every execution must target the broadcast message UID");
    }

    @Test
    public void noTriggersResultsInNoExecutions() {
        mailingListService.triggers = triggers();

        listener.checkStatusChangeMailingListExecution("uid-2", Status.VERIFIED);

        assertEquals(Status.VERIFIED, mailingListService.queriedStatus);
        assertTrue(executionService.executedTriggerIds.isEmpty(),
                "with no matching triggers nothing must be executed");
    }

    @Test
    public void aFailingTriggerDoesNotPreventTheOthersFromRunning() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.failForTriggerId = 2; // the middle trigger throws

        listener.checkStatusChangeMailingListExecution("uid-3", Status.PUBLISHED);

        // The exception from trigger 2 is recorded; triggers 1 and 3 must still have been executed.
        assertEquals(Arrays.asList(1, 2, 3), executionService.attemptedTriggerIds,
                "a failing trigger must not abort the remaining executions");
        assertEquals(Arrays.asList(1, 3), executionService.executedTriggerIds,
                "trigger 2 failed, so only 1 and 3 completed");
        assertEquals(List.of("2:uid-3:simulated trigger failure for 2"), executionService.recordedFailures,
                "the failure of trigger 2 is recorded with its message and cause (MAILLIST2)");
    }

    @Test
    public void aFailureRecordThatFailsDoesNotPreventTheOthersFromRunning() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.failForTriggerId = 2;
        executionService.failRecording = true;

        listener.checkStatusChangeMailingListExecution("uid-4", Status.PUBLISHED);

        assertEquals(Arrays.asList(1, 3), executionService.executedTriggerIds,
                "a record that cannot be stored is logged, and the remaining triggers still run");
    }

    // --------------------------------------------------------------------------------------------- //

    private static List<MailingListTrigger> triggers(Integer... ids) {
        List<MailingListTrigger> list = new ArrayList<>();
        for (Integer id : ids) {
            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setId(id);
            list.add(trigger);
        }
        return list;
    }

    /** Returns a preset trigger list and records the status it was queried with. */
    private static final class RecordingMailingListService extends MailingListService {
        List<MailingListTrigger> triggers = new ArrayList<>();
        Status queriedStatus;

        // PORT: MailingListService now has only a constructor-injection constructor
        // (UserService, ContactService); the test never used those collaborators, so we
        // forward nulls — the overridden findStatusChangeTriggers never touches them.
        RecordingMailingListService() {
            super(null, null);
        }

        @Override
        public List<MailingListTrigger> findStatusChangeTriggers(Status status) {
            this.queriedStatus = status;
            return triggers;
        }
    }

    /** Records every trigger execution (attempted and completed) and can fail one chosen trigger id. */
    private static final class RecordingMailingListExecutionService extends MailingListExecutionService {
        final List<Integer> attemptedTriggerIds = new ArrayList<>();
        final List<Integer> executedTriggerIds = new ArrayList<>();
        final List<String> executedUids = new ArrayList<>();
        final List<String> recordedFailures = new ArrayList<>();
        Integer failForTriggerId;
        boolean failRecording;

        // PORT: MailingListExecutionService now has only a constructor-injection constructor
        // (NiordApp, MessageSearchService, MessageQueryService, FmTemplateService, JsResourceService,
        // DictionaryService, MessageSeriesService); the test never used those collaborators, so we forward
        // nulls — the overridden executeStatusChangeTrigger never touches them.
        RecordingMailingListExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void executeStatusChangeTrigger(Integer triggerId, String messageUid) throws Exception {
            attemptedTriggerIds.add(triggerId);
            if (failForTriggerId != null && failForTriggerId.equals(triggerId)) {
                throw new Exception("simulated trigger failure for " + triggerId);
            }
            executedTriggerIds.add(triggerId);
            executedUids.add(messageUid);
        }

        @Override
        public void recordStatusChangeTriggerFailure(Integer triggerId, String messageUid, Exception cause) {
            if (failRecording) {
                throw new IllegalStateException("simulated record failure for " + triggerId);
            }
            recordedFailures.add(triggerId + ":" + messageUid + ":" + cause.getMessage());
        }
    }
}
