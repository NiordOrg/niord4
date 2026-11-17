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

package org.niord.core.publication.series.batch;

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.publication.series.PublicationSeries;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The domain guard of the series import, row by row.
 *
 * The interactive writes refuse an admin who changes another domain's series,
 * or places a series in a domain they are not administering from. The import
 * reads a file that can name any series and any owner, so every row is held to
 * the same rule against the domain the upload was made from -- which is all the
 * batch thread knows about the caller. A refused row is skipped by the
 * processor; this pins the decision itself.
 */
public class BatchPublicationSeriesImportDomainTest {

    private static Domain domain(String id) {
        Domain d = new Domain();
        d.setDomainId(id);
        return d;
    }

    private static PublicationSeries ownedBy(Domain owner) {
        PublicationSeries s = new PublicationSeries();
        s.setSeriesId("import-domain-fixture");
        s.setDomain(owner);
        return s;
    }

    private static final Domain MINE = domain("niord-nm");
    private static final Domain THEIRS = domain("niord-fa");

    @Test
    public void arowInTheCallersOwnDomainPasses() {
        assertNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(MINE), MINE, MINE),
                "an update of the caller's own series, naming the caller's domain, must go through");
        assertNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(MINE), null, MINE),
                "a document that names no domain leaves the stored owner alone, and that owner is the "
                        + "caller's");
        assertNull(BatchPublicationSeriesImportProcessor.domainRefusal(null, domain("niord-nm"), MINE),
                "a new series placed in the caller's domain must go through; the ids are compared, "
                        + "not the objects");
    }

    @Test
    public void anewSeriesNamingNoDomainIsLeftToTheHardRules() {
        // Not this check's refusal: a new series with no owner is dropped by the
        // hard rules with a sentence of its own, as it always was.
        assertNull(BatchPublicationSeriesImportProcessor.domainRefusal(null, null, MINE));
    }

    @Test
    public void anotherDomainsSeriesIsRefused() {
        String why = BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(THEIRS), null, MINE);
        assertNotNull(why, "the import overwrote a series owned by another domain");
        assertTrue(why.contains("niord-fa"), "the refusal names the owner to import from: " + why);

        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(THEIRS), MINE, MINE),
                "naming the caller's own domain must not take another domain's series over");
    }

    @Test
    public void placingASeriesInAnotherDomainIsRefused() {
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(null, THEIRS, MINE),
                "the import created a series straight into another domain");
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(MINE), THEIRS, MINE),
                "the import moved the caller's own series out to another domain");
    }

    @Test
    public void animportStartedFromNoDomainWritesNothingThatNamesOne() {
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(MINE), null, null),
                "a caller sitting at no desk updated a series");
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(null, MINE, null),
                "a caller sitting at no desk created a series in a domain");
    }

    @Test
    public void anownerlessStoredSeriesIsNotAdoptedByAnImport() {
        // An ownerless row is claimed through the owner transfer, as an act with a
        // name on it; the import must not adopt it by writing a domain into it.
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(null), MINE, MINE));
        assertNotNull(BatchPublicationSeriesImportProcessor.domainRefusal(ownedBy(null), null, MINE));
    }
}
