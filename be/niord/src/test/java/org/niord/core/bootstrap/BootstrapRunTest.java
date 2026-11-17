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
package org.niord.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of one bootstrap attempt ({@link BootstrapRun}, docs/code-data-config-plan.md §7.2): an entry counts as
 * created earlier only when an archive with a lower index of the live run recorded it; never for the same archive,
 * another run, a run that has ended, a job without the token, or outside a run.
 */
class BootstrapRunTest {

    private static BatchData job(Object token) {
        BatchData job = new BatchData();
        Map<String, Object> properties = new HashMap<>();
        if (token != null) {
            properties.put(BootstrapRun.PROPERTY, token);
        }
        job.setProperties(properties);
        return job;
    }

    @Test
    void anEntryALowerArchiveOfTheLiveRunCreatedCountsAsCreatedEarlier() {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        BootstrapRun.Scope first = registry.scopeOf(job(BootstrapRun.token(run, 0)));
        BootstrapRun.Scope second = registry.scopeOf(job(BootstrapRun.token(run, 1)));
        BootstrapRun.Scope third = registry.scopeOf(job(BootstrapRun.token(run, 2)));
        assertEquals(run, second.runId());
        assertEquals(1, second.archive());

        first.created(BootstrapRun.Kind.PARAM_TYPE, "it_composite");
        second.created(BootstrapRun.Kind.REPORT, "it-report");

        assertTrue(second.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "it_composite"));
        assertTrue(third.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "it_composite"));
        assertTrue(third.createdEarlier(BootstrapRun.Kind.REPORT, "it-report"));
        assertFalse(first.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "it_composite"), "the same archive");
        assertFalse(second.createdEarlier(BootstrapRun.Kind.REPORT, "it-report"), "the same archive");
        assertFalse(second.createdEarlier(BootstrapRun.Kind.PROMULGATION_TYPE, "it_composite"), "another kind");
        assertFalse(second.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "other"), "another entry");
    }

    @Test
    void anEntryKeepsTheArchiveThatCreatedItFirst() {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        BootstrapRun.Scope first = registry.scopeOf(job(BootstrapRun.token(run, 0)));
        BootstrapRun.Scope second = registry.scopeOf(job(BootstrapRun.token(run, 1)));

        first.created(BootstrapRun.Kind.CATEGORY, "7");
        second.created(BootstrapRun.Kind.CATEGORY, "7");

        assertTrue(second.createdEarlier(BootstrapRun.Kind.CATEGORY, "7"));
    }

    /**
     * createdBy names the archive of the live run that created an entry first, whichever archive asks; -1 for an
     * entry no archive created, outside a run and once the run has ended
     */
    @Test
    void createdByNamesTheArchiveThatCreatedTheEntryFirst() {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        BootstrapRun.Scope first = registry.scopeOf(job(BootstrapRun.token(run, 0)));
        BootstrapRun.Scope second = registry.scopeOf(job(BootstrapRun.token(run, 1)));
        BootstrapRun.Scope third = registry.scopeOf(job(BootstrapRun.token(run, 2)));

        second.created(BootstrapRun.Kind.PARAM_TYPE, "it_list");
        third.created(BootstrapRun.Kind.PARAM_TYPE, "it_list");
        first.created(BootstrapRun.Kind.PARAM_TYPE, "it_composite");
        second.created(BootstrapRun.Kind.PARAM_TYPE, "it_composite");

        assertEquals(1, third.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_list"), "the creating archive's index");
        assertEquals(1, first.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_list"), "whichever archive asks");
        assertEquals(0, third.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_composite"),
                "the first of two archives that created it");
        assertEquals(-1, third.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_unknown"), "an entry no archive created");
        assertEquals(-1, third.createdBy(BootstrapRun.Kind.REPORT, "it_list"), "another kind's key");
        assertEquals(-1, third.createdBy(BootstrapRun.Kind.PARAM_TYPE, null), "no key");
        assertEquals(-1, BootstrapRun.Scope.NONE.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_list"), "outside a run");

        registry.end(run);
        assertEquals(-1, third.createdBy(BootstrapRun.Kind.PARAM_TYPE, "it_list"), "after the run has ended");
    }

    @Test
    void templateKeysAreComparedCaseInsensitively() {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.scopeOf(job(BootstrapRun.token(run, 0))).created(BootstrapRun.Kind.TEMPLATE, "templates/tmpl/A.ftl");

        BootstrapRun.Scope later = registry.scopeOf(job(BootstrapRun.token(run, 1)));
        assertTrue(later.createdEarlier(BootstrapRun.Kind.TEMPLATE, "templates/tmpl/a.FTL"));
        assertFalse(later.createdEarlier(BootstrapRun.Kind.REPORT, "templates/tmpl/a.FTL"));

        registry.scopeOf(job(BootstrapRun.token(run, 0))).created(BootstrapRun.Kind.REPORT, "Report");
        assertFalse(later.createdEarlier(BootstrapRun.Kind.REPORT, "report"), "only template paths ignore case");
        assertFalse(later.createdEarlier(BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey("aton", "k")));
    }

    @Test
    void anotherRunARunThatHasEndedAndAJobWithoutTheTokenAnswerNothing() {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.scopeOf(job(BootstrapRun.token(run, 0))).created(BootstrapRun.Kind.PARAM_TYPE, "t");
        BootstrapRun.Scope later = registry.scopeOf(job(BootstrapRun.token(run, 1)));
        assertTrue(later.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "t"));

        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job(BootstrapRun.token("another-run", 1))));
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job(null)), "a job without the token");
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(null), "no job (a handler driven by hand)");
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job(run)), "a token without an archive");
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job(run + "/x")), "an archive that is no number");
        assertSame(BootstrapRun.Scope.NONE, BootstrapRun.scopeOf(null, job(BootstrapRun.token(run, 1))),
                "no registry (a handler built by hand)");

        registry.end("another-run");
        assertTrue(later.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "t"), "only the live run's id ends it");

        registry.end(run);
        assertFalse(later.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, "t"), "a scope of the ended run");
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job(BootstrapRun.token(run, 1))), "the ended run's token");

        // A new run starts with nothing recorded, and the old run's scope stays inert
        String next = registry.begin();
        assertFalse(registry.scopeOf(job(BootstrapRun.token(next, 1))).createdEarlier(BootstrapRun.Kind.PARAM_TYPE,
                "t"));
        later.created(BootstrapRun.Kind.PARAM_TYPE, "u");
        assertFalse(registry.scopeOf(job(BootstrapRun.token(next, 2))).createdEarlier(BootstrapRun.Kind.PARAM_TYPE,
                "u"), "an ended run's scope records nothing");
    }

    /** A dictionary's name and a key are separated by '\0', which a key cannot hold: no two pairs share a key */
    @Test
    void dictionaryKeysOfTwoPairsNeverCollide() {
        assertNotEquals(BootstrapRun.dictionaryKey("a", "b\nc"), BootstrapRun.dictionaryKey("a\nb", "c"));

        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.scopeOf(job(BootstrapRun.token(run, 0)))
                .created(BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey("a", "b\nc"));
        BootstrapRun.Scope later = registry.scopeOf(job(BootstrapRun.token(run, 1)));
        assertTrue(later.createdEarlier(BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey("a", "b\nc")));
        assertFalse(later.createdEarlier(BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey("a\nb", "c")),
                "another dictionary and key with the same characters");
    }

    /**
     * The job log's line of an entry an earlier archive of the run created: the notes, what was added, what was
     * replaced (the old value and the new), what was kept; or that nothing was to add
     */
    @Test
    void anAdditionsLineSaysWhatWasAddedReplacedAndKept() {
        String earlier = " (an earlier archive of this bootstrap run created it): ";
        assertEquals("Report r" + earlier + "added domain d; replaced the name a → b; kept domain x not found",
                new BootstrapRun.Additions("Report r", List.of("domain d"),
                        List.of(BootstrapRun.Additions.change("the name", "a", "b")),
                        List.of("domain x not found")).line());
        assertEquals("Parameter type t" + earlier + "nothing to add",
                new BootstrapRun.Additions("Parameter type t", List.of(), List.of(), List.of()).line());
        assertEquals("Parameter type t" + earlier + "replaced its values [k1] → [k1, k2]",
                new BootstrapRun.Additions("Parameter type t", List.of(),
                        List.of(BootstrapRun.Additions.change("its values", List.of("k1"), List.of("k1", "k2"))),
                        List.of()).line());
        assertEquals("Category 1" + earlier + "nothing to add; kept the MRN (a matching key)",
                new BootstrapRun.Additions("Category 1", List.of(), List.of(), List.of("the MRN (a matching key)"))
                        .line());
        assertEquals("Category 2" + earlier + "activated by its new active child 3",
                new BootstrapRun.Additions("Category 2", List.of(), List.of(), List.of(),
                        List.of("activated by its new active child 3")).line());
        assertEquals("the requirement none → MANDATORY",
                BootstrapRun.Additions.change("the requirement", null, "MANDATORY"), "a value the row lacked");
    }

    @Test
    void outsideARunNothingIsRecordedOrAnswered() {
        BootstrapRun.Scope none = BootstrapRun.Scope.NONE;
        none.created(BootstrapRun.Kind.TEMPLATE, "templates/x.ftl");
        assertFalse(none.createdEarlier(BootstrapRun.Kind.TEMPLATE, "templates/x.ftl"));

        BootstrapRun registry = new BootstrapRun();
        assertSame(BootstrapRun.Scope.NONE, registry.scopeOf(job("any/1")), "no run has begun");
    }
}
