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
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.test.batch.BatchReaderTestSupport;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link BatchPublicationCategoryImportWriter#writeItems(List)} — the persist half of the
 * publication-category import batch job. The writer branches per item on {@link PublicationCategory#isNew()}:
 * a brand-new category is routed to {@link PublicationCategoryService#createPublicationCategory(PublicationCategory)},
 * while an existing one is routed to {@link PublicationCategoryService#saveEntity(Object)}.
 *
 * <p>Driven entirely without JBeret, CDI or a DB by sub-classing the writer, silencing the batch-folder
 * logger via {@link BatchReaderTestSupport#quietLogger(String)}, and wiring a Mockito-mocked service into the
 * package-private {@code publicationCategoryService} {@code @Inject} field. The items are Mockito-mocked
 * {@link PublicationCategory} instances whose {@code isNew()} flag selects the branch. Every test asserts the
 * exact service method invoked (with the captured argument) plus a {@code never()} on the opposite branch, so
 * the create-vs-update routing is pinned independently of any framework wiring and survives a future Spring port.
 */
public class BatchPublicationCategoryImportWriterTest {

    /** A writer that silences the batch-folder logger and exposes a wiring hook for the mocked service. */
    static class TestableWriter extends BatchPublicationCategoryImportWriter {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchPublicationCategoryImportWriterTest");
        }

        void wire(PublicationCategoryService service) {
            this.publicationCategoryService = service;
        }
    }

    /** A mocked category whose {@code isNew()} flag drives the writer's create-vs-update branch. */
    private static PublicationCategory categoryWithNew(boolean isNew) {
        PublicationCategory category = mock(PublicationCategory.class);
        when(category.isNew()).thenReturn(isNew);
        return category;
    }

    private static TestableWriter writerWith(PublicationCategoryService service) {
        TestableWriter writer = new TestableWriter();
        writer.wire(service);
        return writer;
    }

    // --------------------------------------------------------------------------------------------- //
    // new category -> create branch
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void newCategoryIsCreatedNotSaved() throws Exception {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        TestableWriter writer = writerWith(service);
        PublicationCategory fresh = categoryWithNew(true);

        writer.writeItems(Collections.singletonList(fresh));

        // A new category must be routed to create(), receiving the very same instance...
        ArgumentCaptor<PublicationCategory> captor = ArgumentCaptor.forClass(PublicationCategory.class);
        verify(service).createPublicationCategory(captor.capture());
        assertSame(fresh, captor.getValue(),
                "the new category instance must be passed through unchanged to create");
        // ...and the update path must not be touched.
        verify(service, never()).saveEntity(any());
    }

    // --------------------------------------------------------------------------------------------- //
    // existing category -> update/save branch
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void existingCategoryIsSavedNotCreated() throws Exception {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        TestableWriter writer = writerWith(service);
        PublicationCategory existing = categoryWithNew(false);

        writer.writeItems(Collections.singletonList(existing));

        // An existing category must be routed to saveEntity(), receiving the very same instance...
        ArgumentCaptor<PublicationCategory> captor = ArgumentCaptor.forClass(PublicationCategory.class);
        verify(service).saveEntity(captor.capture());
        assertSame(existing, captor.getValue(),
                "the existing category instance must be passed through unchanged to saveEntity");
        // ...and the create path must not be touched.
        verify(service, never()).createPublicationCategory(any());
    }

    // --------------------------------------------------------------------------------------------- //
    // mixed batch -> each item routed independently, in order
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void mixedBatchRoutesEachItemToItsOwnBranch() throws Exception {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        TestableWriter writer = writerWith(service);
        PublicationCategory fresh1 = categoryWithNew(true);
        PublicationCategory existing = categoryWithNew(false);
        PublicationCategory fresh2 = categoryWithNew(true);

        writer.writeItems(Arrays.asList(fresh1, existing, fresh2));

        // The two new categories each land in create() exactly once...
        verify(service).createPublicationCategory(fresh1);
        verify(service).createPublicationCategory(fresh2);
        verify(service, times(2)).createPublicationCategory(any());
        // ...the single existing one lands in saveEntity() exactly once, and no cross-routing occurs.
        verify(service).saveEntity(existing);
        verify(service, times(1)).saveEntity(any());
        verify(service, never()).saveEntity(fresh1);
        verify(service, never()).saveEntity(fresh2);
        verify(service, never()).createPublicationCategory(existing);
    }

    // --------------------------------------------------------------------------------------------- //
    // empty batch -> no persistence calls at all
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void emptyBatchPersistsNothing() throws Exception {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        TestableWriter writer = writerWith(service);

        writer.writeItems(Collections.emptyList());

        // No items means the service must be left entirely untouched.
        verifyNoInteractions(service);
    }

    // --------------------------------------------------------------------------------------------- //
    // duplicate new categories -> create called once per item (no de-dup)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void repeatedNewItemsAreEachCreated() throws Exception {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        TestableWriter writer = writerWith(service);
        PublicationCategory fresh = categoryWithNew(true);

        // The same new instance appearing twice in a chunk must be created twice — the writer does no de-dup.
        writer.writeItems(Arrays.asList(fresh, fresh));

        verify(service, times(2)).createPublicationCategory(fresh);
        verify(service, never()).saveEntity(any());
    }
}
