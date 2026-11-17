/*
 * Copyright 2026 Danish Emergency Management Agency.
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
import org.niord.core.message.MessageSearchParams;
import org.niord.core.publication.PublicationSearchParams;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The public tier of the legacy publication search, as a pure narrowing of the params.
 *
 * The three fields it sets are the three a request could otherwise use to widen an
 * anonymous search to drafts, templates or internal categories, so each is
 * overwritten rather than defaulted; the narrower filters are left as asked. The page size is the
 * caller's too, and is kept within the ceiling the message searches apply.
 */
public class PublicationPublicTierTest {

    @Test
    public void thepublicTierOverwritesWhatTheRequestAskedFor() {
        PublicationSearchParams params = new PublicationSearchParams()
                .statuses(Set.of(PublicationStatus.DRAFT, PublicationStatus.RECORDING))
                .mainType(PublicationMainType.TEMPLATE)
                .published(Boolean.FALSE)
                .category("dk-dma-publications")
                .title("EfS");

        PublicationRestService.toPublicTier(params);

        assertEquals(Set.of(PublicationStatus.ACTIVE), params.getStatuses(),
                "the status a request names must not reach an anonymous search");
        assertEquals(PublicationMainType.PUBLICATION, params.getMainType(),
                "an anonymous search must not reach the templates");
        assertEquals(Boolean.TRUE, params.getPublished(),
                "an anonymous search must not reach a non-publishing category");
        assertEquals("dk-dma-publications", params.getCategory(), "a narrower filter is kept");
        assertEquals("EfS", params.getTitle(), "a narrower filter is kept");
    }

    @Test
    public void thePublicTierPageSizeIsKeptWithinTheMessageSearchCeiling() {
        assertEquals(MessageSearchParams.MAX_PAGE_SIZE, PublicationRestService.publicTierMaxSize(Integer.MAX_VALUE),
                "an anonymous read must not ask for more rows than the message searches allow");
        assertEquals(1000, PublicationRestService.publicTierMaxSize(1001));
        assertEquals(1000, PublicationRestService.publicTierMaxSize(1000));
        assertEquals(100, PublicationRestService.publicTierMaxSize(100), "a size below the ceiling is kept");
        // Below 1 the search would otherwise answer with the whole list
        assertEquals(1, PublicationRestService.publicTierMaxSize(0));
        assertEquals(1, PublicationRestService.publicTierMaxSize(-5));
    }
}
