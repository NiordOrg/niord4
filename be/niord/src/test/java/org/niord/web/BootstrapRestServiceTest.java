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
import org.niord.core.bootstrap.BootstrapMarker;
import org.niord.core.bootstrap.BootstrapRefusedException;
import org.niord.core.bootstrap.BootstrapRunner;
import org.niord.core.bootstrap.StartUpState;
import org.niord.core.web.NiordWebException;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bootstrap command waits for the start-up (docs/code-data-config-plan.md §7.2): until every start-up listener
 * has run, the default texts and settings included, {@code GET} and {@code POST /rest/bootstrap} answer 503, so a
 * caller asks again and the bootstrap's imports never run beside the start-up's own writes.
 */
class BootstrapRestServiceTest {

    final BootstrapMarker marker = mock(BootstrapMarker.class);
    final BootstrapRunner runner = mock(BootstrapRunner.class);
    final StartUpState startUp = new StartUpState();
    final BootstrapRestService service = new BootstrapRestService(marker, runner, startUp);

    @Test
    void getAnswers503UntilTheStartUpIsDone() {
        when(marker.read()).thenReturn(Optional.of(new BootstrapMarker.State(BootstrapMarker.PENDING, null, null,
                "4.0.0", null)));

        NiordWebException e = assertThrows(NiordWebException.class, service::getState);
        assertEquals(503, e.getStatusCode().value());
        assertTrue(e.getReason().contains("still starting"), e.getReason());

        startUp.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        assertEquals(503, assertThrows(NiordWebException.class, service::getState).getStatusCode().value());

        startUp.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        assertEquals(BootstrapMarker.PENDING, service.getState().state());
    }

    @Test
    void postAnswers503BeforeItReadsAPartUntilTheStartUpIsDone() throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("archive", "niord-bootstrap.zip", "application/zip", new byte[] {1}));

        NiordWebException e = assertThrows(NiordWebException.class, () -> service.bootstrap(request));
        assertEquals(503, e.getStatusCode().value());
        assertTrue(e.getReason().contains("still starting"), e.getReason());
        verify(runner, never()).claim(any());
    }

    /** An archive the archive rules accept: one domain import */
    private static byte[] anArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("batch-set.json"));
            zip.write("[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\"}]"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("domains.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** A claim refused because the database holds data is answered 409, naming the table (§7.2, D4) */
    @Test
    void aDataRefusalIsAnswered409WithTheTable() throws Exception {
        startUp.onReadinessChange(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        doThrow(new BootstrapRefusedException("The database holds data (a row in \"Domain\"): a bootstrap runs "
                + "only on a new, empty database", BootstrapRefusedException.DATA)).when(runner).claim(any());
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("archive", "niord-bootstrap.zip", "application/zip", anArchive()));

        NiordWebException e = assertThrows(NiordWebException.class, () -> service.bootstrap(request));

        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("\"Domain\""), e.getReason());
        verify(runner, never()).apply(any());
    }
}
