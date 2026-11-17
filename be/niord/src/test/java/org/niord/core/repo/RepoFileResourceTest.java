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
package org.niord.core.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RepoFileResource}: the row is read once however many streams a request opens on the resource. Spring's
 * region writer opens the resource's stream again for every range that starts before its position, so a request
 * of a hundred descending ranges on a file would otherwise read its row a hundred times.
 */
class RepoFileResourceTest {

    private static final RepoFileInfo FILE = new RepoFileInfo("messages/a/b/file.pdf", "messages/a/b", "file.pdf",
            5, null, Instant.EPOCH, Instant.EPOCH, null);

    private static final byte[] BYTES = "hello".getBytes(StandardCharsets.UTF_8);

    @Test
    void theRowIsReadOnceHoweverManyStreamsAreOpened() throws IOException {
        RepoStore store = mock(RepoStore.class);
        when(store.readAll(FILE)).thenReturn(BYTES);
        RepoFileResource resource = new RepoFileResource(store, FILE);

        try (InputStream first = resource.getInputStream(); InputStream second = resource.getInputStream()) {
            assertArrayEquals(BYTES, first.readAllBytes(), "the first stream reads the row");
            assertArrayEquals(BYTES, second.readAllBytes(), "the second stream reads the same bytes, whole again");
        }

        verify(store, times(1)).readAll(FILE);
    }

    @Test
    void aReadThatFailedIsTriedAgainByTheNextStream() throws IOException {
        RepoStore store = mock(RepoStore.class);
        when(store.readAll(FILE)).thenThrow(new IOException("replaced since it was looked up")).thenReturn(BYTES);
        RepoFileResource resource = new RepoFileResource(store, FILE);

        assertThrows(IOException.class, resource::getInputStream, "the first stream reports the failed read");
        try (InputStream second = resource.getInputStream()) {
            assertArrayEquals(BYTES, second.readAllBytes(), "nothing is remembered of a failed read");
        }

        verify(store, times(2)).readAll(FILE);
    }
}
