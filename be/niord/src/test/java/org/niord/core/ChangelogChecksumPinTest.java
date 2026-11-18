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
package org.niord.core;

import liquibase.ChecksumVersion;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the checksum of every changeset in the production master changelog.
 * <p>
 * An applied changeset is immutable: Liquibase records its checksum in {@code DATABASECHANGELOG}
 * and refuses to start an application whose changelog computes another one for the same id. In a
 * formatted SQL changelog <em>the comment lines belong to the changeset's text</em>, so editing a
 * comment changes the checksum as surely as editing a statement. The integration tests start from
 * an empty database and cannot see that; every running database can. On 2026-10-06 a path in a
 * comment of {@code V001__baseline_schema.sql} was edited and both staging environments went down on
 * {@code Validation Failed: changesets check sum} — this test is the guard that failure asked for.
 * <p>
 * A new changeset is added here with its checksum, deliberately. A changeset whose checksum must
 * change (there is almost never a reason) is handled with Liquibase's {@code validCheckSum}, not by
 * editing this map and shipping a broken start-up to every environment.
 * <p>
 * One changeset has changed that way: {@code 001-baseline-schema} lost its comment on the postgis
 * extension, which a database user that is not a superuser may not write
 * ({@code docs/database-user-plan.md}). Its file names the checksum the running databases hold as
 * valid, and {@link #theBaselineStaysValidForADatabaseThatAppliedItBeforeItChanged()} holds it to that.
 */
class ChangelogChecksumPinTest {

    private static final String MASTER = "db/changelog/db.changelog-master.yaml";

    /**
     * What a database built before November 2026 holds for {@code 001-baseline-schema}: the checksum of the
     * file as it was until the comment on the postgis extension went.
     */
    private static final String BASELINE_BEFORE_IT_CHANGED = "9:ac5891e50cfedc4d905d8f8690591ef9";

    /** Changeset id (author:id) to its checksum, as Liquibase computes it for the applied files. */
    private static final Map<String, String> PINNED = new LinkedHashMap<>();
    static {
        PINNED.put("niord:001-baseline-schema", "9:d07287bf069aa1da269e2c721a8f2d38");
        PINNED.put("niord:002-message-search-text", "9:1913c978c09e29b46eb75d637b446582");
        PINNED.put("niord:003-secom", "9:2daad5cbe3a917cc086c77892d384c53");
        PINNED.put("niord:004-publication-series", "9:a44ce36ddbbad343c829df4fca334bfb");
        PINNED.put("niord:005-retire-map-image-settings", "9:40efbb46119dcdfb6c7fe257e7757bf0");
        PINNED.put("niord:006-defaults-ledger", "9:b66dfd36f36393a7bfa760ab6081ec0b");
        PINNED.put("niord:007-bootstrap-state", "9:36e4524848ccaa4ccc05fadc46c3ae1c");
        PINNED.put("niord:008-script-resource-history-source", "9:c1a26c5118d503fccdfe0dd12f1b204c");
        PINNED.put("niord:009-repo-files", "9:a93f94b3c3e5fa1eb9bcfb3ea39abfda");
        PINNED.put("niord:010-message-list-index-default", "9:7afd8f89e492f46dd854d1aa52c1bba3");
        PINNED.put("niord:011-message-filter-parameters-text", "9:84ef76d49e0b274e2dd56ca63cb46c1f");
        PINNED.put("niord:012-mailing-list-status-change", "9:200bba76efdcb9e894c4282b39b5435f");
    }

    private DatabaseChangeLog master() throws Exception {
        ResourceAccessor accessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        return ChangeLogParserFactory.getInstance()
                .getParser(MASTER, accessor)
                .parse(MASTER, new ChangeLogParameters(), accessor);
    }

    @Test
    void appliedChangesetsKeepTheirChecksums() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (ChangeSet changeSet : master().getChangeSets()) {
            actual.put(changeSet.getAuthor() + ":" + changeSet.getId(),
                    changeSet.generateCheckSum(ChecksumVersion.latest()).toString());
        }

        assertEquals(new TreeMap<>(PINNED), actual,
                "The checksum of an applied changeset changed, or a changeset was added without its pin. "
                + "An applied changeset — its comments included — is immutable: every running database "
                + "holds the recorded checksum and Liquibase refuses to start on a mismatch. Restore the "
                + "file, or for a NEW changeset add its id and checksum here. Computed now: " + actual);
    }

    /**
     * The start-up check itself, {@link ChangeSet#isCheckSumValid}, given what the running databases hold for
     * the baseline: the checksum from before its comment on the postgis extension went. Without the
     * {@code validCheckSum} line in the file this is false, and every database built before the change refuses
     * to start. Another changeset's checksum is not valid for it, so the line admits that one checksum only.
     */
    @Test
    void theBaselineStaysValidForADatabaseThatAppliedItBeforeItChanged() throws Exception {
        ChangeSet baseline = null;
        for (ChangeSet changeSet : master().getChangeSets()) {
            if ("001-baseline-schema".equals(changeSet.getId())) {
                baseline = changeSet;
            }
        }
        assertNotNull(baseline, "the master changelog holds 001-baseline-schema");

        assertTrue(baseline.isCheckSumValid(CheckSum.parse(BASELINE_BEFORE_IT_CHANGED)),
                "A database that applied 001-baseline-schema before its comment on the postgis extension went "
                + "holds " + BASELINE_BEFORE_IT_CHANGED + " for it. The file must name that checksum in a "
                + "--validCheckSum line, or Liquibase refuses to start on every such database.");
        assertFalse(baseline.isCheckSumValid(CheckSum.parse(PINNED.get("niord:002-message-search-text"))),
                "validCheckSum admits the one old checksum, not any checksum");
    }
}
