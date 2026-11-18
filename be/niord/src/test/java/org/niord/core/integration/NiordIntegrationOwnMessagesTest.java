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
package org.niord.core.integration;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSearchService;
import org.niord.model.search.PagedSearchResultVo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Niord integration's look-up of its own copies of a series ({@code fetchOwnMessages}). A failed look-up stops
 * the run: answered as "no own copies", it would make every warning of the series an import, a second copy of each.
 */
class NiordIntegrationOwnMessagesTest {

    private static Message message(String uid, String legacyId) {
        Message m = new Message();
        m.setUid(uid);
        m.setLegacyId(legacyId);
        return m;
    }

    @Test
    void aFailedLookUpOfTheOwnCopiesIsThrownNotReadAsNone() throws Exception {
        MessageSearchService searchService = mock(MessageSearchService.class);
        when(searchService.searchOrFail(any())).thenThrow(new PersistenceException("the search failed"));
        NiordIntegrationExecutionService service =
                new NiordIntegrationExecutionService(null, null, searchService, null, null, null, null);

        assertThrows(PersistenceException.class, () -> service.fetchOwnMessages("s"));
        verify(searchService, never()).search(any());
    }

    @Test
    void theOwnCopiesAreThePublishedMessagesWithALegacyId() throws Exception {
        MessageSearchService searchService = mock(MessageSearchService.class);
        PagedSearchResultVo<Message> result = new PagedSearchResultVo<>();
        result.setData(List.of(message("own", null), message("imported", "remote-1")));
        when(searchService.searchOrFail(any())).thenReturn(result);
        NiordIntegrationExecutionService service =
                new NiordIntegrationExecutionService(null, null, searchService, null, null, null, null);

        List<Message> own = service.fetchOwnMessages("s");

        assertEquals(List.of("imported"), own.stream().map(Message::getUid).toList());
    }
}
