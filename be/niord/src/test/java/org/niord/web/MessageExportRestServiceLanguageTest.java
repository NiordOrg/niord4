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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageExportService;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.search.PagedSearchResultVo;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The message export ({@code GET /rest/message-io/export.zip}) searches every language of the messages and hands
 * the language the request names to the archive, for the labels of its preview page. FAILS without it: the language
 * was dropped before the search and the page's labels were never in it.
 */
class MessageExportRestServiceLanguageTest {

    @Test
    void theRequestsLanguageReachesTheArchiveAndTheSearchHasEveryLanguage() throws Exception {
        MessageSearchRestService search = mock(MessageSearchRestService.class);
        PagedSearchResultVo<SystemMessageVo> result = new PagedSearchResultVo<>();
        result.setData(new ArrayList<>());
        when(search.searchSystemMessages(any())).thenReturn(result);
        MessageExportService export = mock(MessageExportService.class);
        MessageExportRestService service = new MessageExportRestService(search, export, mock(DomainService.class),
                mock(MessageTagService.class));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/message-io/export.zip");
        request.addParameter("lang", "da");
        StreamingResponseBody body = service.generateZipArchiveForSearch(request).getBody();
        assertNotNull(body);
        body.writeTo(new ByteArrayOutputStream());

        verify(export).export(eq(result), eq("da"), any(OutputStream.class));
        ArgumentCaptor<MessageSearchParams> params = ArgumentCaptor.forClass(MessageSearchParams.class);
        verify(search).searchSystemMessages(params.capture());
        assertNull(params.getValue().getLanguage(), "the archive holds every language of the messages");
    }
}
