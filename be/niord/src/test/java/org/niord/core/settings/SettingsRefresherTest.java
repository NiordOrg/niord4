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
package org.niord.core.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which node a change is published as ({@link SettingsRefresher}): a key saved on this node is published as saved
 * here whichever refresh moves its field. The poll can meet a save's committed value before the save's own refresh
 * runs ({@link SettingsRefresher#refreshSaved}); the change is then still this node's, so that work done once for all
 * nodes, such as building the shared search table again, is done. The settings, the fields and the transaction are
 * stubbed; the integration tests of a save are {@code SettingRefreshITTest} and {@code SettingsSecondNodeITTest}.
 */
class SettingsRefresherTest {

    private static final String KEY = "messageIndexIncludeDeletedMessages";

    private final SettingsService settingsService = mock(SettingsService.class);

    private final SettingInjectionBeanPostProcessor processor = mock(SettingInjectionBeanPostProcessor.class);

    private final List<Object> published = new ArrayList<>();

    private final SettingsRefresher refresher = new SettingsRefresher(settingsService, processor,
            mock(SettingsCache.class, RETURNS_DEEP_STUBS), published::add);

    @BeforeEach
    void followFromCountOne() {
        count(1L);
        refreshes(Set.of());
        refresher.pollForChanges();
        published.clear();
    }

    @AfterEach
    void endTheTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * The save has committed and the poll refreshes the field before the save's own refresh: published once, as saved
     * here, and the save's own refresh, which then finds nothing to change, publishes nothing. FAILS when the change
     * is decided by which refresh moved the field: it is published as found by the poll only.
     */
    @Test
    void aPollThatMeetsASaveHereBeforeItsOwnRefreshPublishesItAsSavedHere() {
        TransactionSynchronizationManager.initSynchronization();
        refresher.savingHere(KEY);
        // The save commits; the poll runs before its afterCommit action
        count(2L);
        refreshes(Set.of(KEY, "mailSender"));
        refresher.pollForChanges();

        refreshes(Set.of());
        refresher.refreshSaved(KEY, 1L, 2L);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertEquals(List.of(new SettingChangedEvent(KEY, true), new SettingChangedEvent("mailSender", false)),
                published, "the save here once, as saved here; another node's save as found by the poll");
    }

    /** Once the save has completed, a later change of the key that the poll finds is another node's */
    @Test
    void aKeyIsSavedHereOnlyUntilItsSaveHasCompleted() {
        TransactionSynchronizationManager.initSynchronization();
        refresher.savingHere(KEY);
        refreshes(Set.of(KEY));
        refresher.refreshSaved(KEY, 1L, 2L);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        count(3L);
        refresher.pollForChanges();

        assertEquals(List.of(new SettingChangedEvent(KEY, true), new SettingChangedEvent(KEY, false)), published);
    }

    /** A save that rolled back is not this node's change: the next change the poll finds is another node's */
    @Test
    void aSaveThatRolledBackLeavesNothingBehind() {
        TransactionSynchronizationManager.initSynchronization();
        refresher.savingHere(KEY);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        count(2L);
        refreshes(Set.of(KEY));
        refresher.pollForChanges();

        assertEquals(List.of(new SettingChangedEvent(KEY, false)), published);
    }

    // ------------------------------------------------------------------------------------------- //

    private void count(Long count) {
        when(settingsService.peek(SettingsRefresher.CHANGE_COUNT.getKey())).thenReturn(count);
    }

    private void refreshes(Set<String> changed) {
        when(processor.refresh(eq(settingsService), any())).thenReturn(changed);
    }

    /** Ends the save's transaction as its transaction manager does */
    private static void complete(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(s -> s.afterCompletion(status));
    }
}
