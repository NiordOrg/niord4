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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RepositoryService#isListedFileName}: a message's files list shows what editors attached, never the
 * generated map images, the image thumbnails or hidden files.
 */
class RepositoryListingFilterTest {

    @Test
    void theCustomMapThumbnailAndHiddenFilesAreNotListed() {
        assertFalse(RepositoryService.isListedFileName("custom_thumb_256.png"));
        assertFalse(RepositoryService.isListedFileName("custom_thumb_1024.png"));
        assertFalse(RepositoryService.isListedFileName(".DS_Store"));
    }

    /** Every row is an upload: a name that looks like a cached thumbnail or a generated map image is an upload too */
    @Test
    void uploadsAreListedWhateverTheirNames() {
        assertTrue(RepositoryService.isListedFileName("chart.png"));
        assertTrue(RepositoryService.isListedFileName("chart_thumb_64.png"), "named like a thumbnail");
        assertTrue(RepositoryService.isListedFileName("map_256.png"), "named like a map image");
        assertTrue(RepositoryService.isListedFileName("map_256_053d3a24b6af.png"));
        assertTrue(RepositoryService.isListedFileName("map_of_the_area.png"));
        assertTrue(RepositoryService.isListedFileName("custom_thumb_256.jpg"), "not the endpoint's name");
    }
}
