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
package org.niord.core.publication.batch;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationService;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Framework-neutral unit tests for {@link BatchPublicationImportWriter#writeItems(List)} — the persist
 * step of the publication import batch job. The writer's only logic is a per-item <b>create-vs-update
 * branch</b>: it looks up an existing publication by {@code publicationId} via
 * {@link PublicationService#findByPublicationId(String)} and routes a {@code null} result to
 * {@link PublicationService#createPublication(Publication)}, otherwise to
 * {@link PublicationService#updatePublication(Publication)}.
 *
 * <p>No JBeret, broker, database or Quarkus container is needed to exercise this decision. The writer is
 * sub-classed ({@link TestableWriter}) to silence the batch-folder logger and expose a wiring hook for a
 * Mockito-stubbed {@link PublicationService} on the package-private {@code publicationService} field. The
 * batch items are mock {@link Publication} collaborators (mocking the data object is acceptable here — the
 * class under test is the <em>writer</em>, not the publication entity).
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>An item with no existing publication is created and never updated.</li>
 *   <li>An item with an existing publication is updated and never created.</li>
 *   <li>A mixed batch routes each item independently — exactly one create and one update, each with the
 *       correct argument (the incoming item, not the looked-up original).</li>
 * </ul>
 */
public class BatchPublicationImportWriterTest {

    /** A writer that silences the batch-folder logger and exposes a wiring hook for the service stub. */
    static class TestableWriter extends BatchPublicationImportWriter {
        @Override
        protected Logger getLog() {
            Logger log = Logger.getLogger("BatchPublicationImportWriterTest");
            log.setUseParentHandlers(false);
            return log;
        }

        void wire(PublicationService s) {
            this.publicationService = s;
        }
    }

    /** Builds a mock publication that reports the given id when looked up by the writer. */
    private static Publication publication(String publicationId) {
        Publication pub = mock(Publication.class);
        when(pub.getPublicationId()).thenReturn(publicationId);
        return pub;
    }

    private static List<Object> batch(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    /**
     * When the lookup by publication id returns {@code null}, the item is a new publication: the writer
     * persists it via {@code createPublication} and must not call {@code updatePublication}.
     */
    @Test
    public void writeItemsCreatesPublicationWhenNoneExists() throws Exception {
        PublicationService service = mock(PublicationService.class);
        Publication p = publication("pub-1");
        when(service.findByPublicationId("pub-1")).thenReturn(null);

        TestableWriter writer = new TestableWriter();
        writer.wire(service);

        writer.writeItems(batch(p));

        verify(service).createPublication(p);
        verify(service, never()).updatePublication(any());
    }

    /**
     * When the lookup by publication id returns an existing publication, the item is an update: the writer
     * routes the <em>incoming</em> item to {@code updatePublication} and must not call {@code createPublication}.
     */
    @Test
    public void writeItemsUpdatesPublicationWhenOneExists() throws Exception {
        PublicationService service = mock(PublicationService.class);
        Publication p = publication("pub-1");
        Publication existing = publication("pub-1");
        when(service.findByPublicationId("pub-1")).thenReturn(existing);

        TestableWriter writer = new TestableWriter();
        writer.wire(service);

        writer.writeItems(batch(p));

        verify(service).updatePublication(p);
        verify(service, never()).createPublication(any());
    }

    /**
     * A single batch containing one brand-new and one already-persisted publication is routed item-by-item:
     * exactly one {@code createPublication} (with the new item) and one {@code updatePublication} (with the
     * existing item), with no cross-talk between the two branches.
     */
    @Test
    public void writeItemsRoutesMixedBatchPerItem() throws Exception {
        PublicationService service = mock(PublicationService.class);

        Publication newPub = publication("pub-new");
        Publication existingPub = publication("pub-existing");
        // Build the looked-up "original" mock on its own line: a publication(...) call inside the
        // when(...).thenReturn(...) argument would nest stubbings and trip Mockito's UnfinishedStubbing.
        Publication existingOriginal = publication("pub-existing");
        when(service.findByPublicationId("pub-new")).thenReturn(null);
        when(service.findByPublicationId("pub-existing")).thenReturn(existingOriginal);

        TestableWriter writer = new TestableWriter();
        writer.wire(service);

        writer.writeItems(batch(newPub, existingPub));

        // The new publication is created exactly once, and never updated.
        verify(service).createPublication(newPub);
        verify(service, never()).createPublication(existingPub);

        // The existing publication is updated exactly once (with the incoming item), and never created.
        verify(service).updatePublication(existingPub);
        verify(service, never()).updatePublication(newPub);
    }
}
