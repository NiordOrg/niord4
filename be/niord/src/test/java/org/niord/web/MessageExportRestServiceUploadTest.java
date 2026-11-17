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
import org.niord.core.batch.BatchService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageExportService;
import org.niord.core.message.MessageTagService;
import org.niord.core.user.Roles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The message archive upload ({@code POST /rest/message-io/import}) with two archives in one request: the
 * endpoint's check reads the form's {@code data} field and replaces it with the job properties, and each archive's
 * job is started with the properties a single archive's job would get.
 */
class MessageExportRestServiceUploadTest {

    /** A message archive holding an empty messages.json */
    private static byte[] archive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("messages.json"));
            zip.write("{\"data\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoArchivesInOneUploadEachStartTheirJobWithTheSeriesOfTheForm() throws Exception {
        DomainService domainService = mock(DomainService.class);
        when(domainService.domainsWithUserRole(Roles.ADMIN)).thenReturn(List.of());
        BatchService batchService = mock(BatchService.class);
        MessageExportRestService service = new MessageExportRestService(
                mock(MessageSearchRestService.class), mock(MessageExportService.class), domainService,
                mock(MessageTagService.class));
        ReflectionTestUtils.setField(service, "batchService", batchService);

        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "first.zip", "application/zip", archive()));
        request.addFile(new MockMultipartFile("file", "second.zip", "application/zip", archive()));
        request.addPart(new MockPart("data", "{\"seriesId\":\"nw-series\",\"preserveStatus\":true}"
                .getBytes(StandardCharsets.UTF_8)));

        String answer = service.importMessages(request);

        assertTrue(answer.contains("with file first.zip"), answer);
        assertTrue(answer.contains("with file second.zip"), answer);
        ArgumentCaptor<String> files = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(batchService, times(2)).startBatchJobWithDataFile(eq("msg-archive-import"), any(InputStream.class),
                files.capture(), properties.capture());
        assertEquals(new HashSet<>(List.of("first.zip", "second.zip")), new HashSet<>(files.getAllValues()));
        for (Map<String, Object> started : properties.getAllValues()) {
            assertEquals("nw-series", started.get("seriesId"), started.toString());
            assertEquals(true, started.get("preserveStatus"), started.toString());
            assertFalse(started.containsKey("data"), "the job gets the properties, not the form's data field");
        }
    }
}
