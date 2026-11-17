/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.sequence;

import org.springframework.transaction.IllegalTransactionStateException;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link SequenceService}, run against the seeded PostgreSQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>This service is correctness-sensitive: {@code peekNextValue}/{@code takeNextValue}/{@code nextValue}/
 * {@code resetNextValue} drive id/number generation, so the tests assert the EXACT numeric values
 * produced and the EXACT persisted {@link SequenceEntity#getNextValue()} state.
 *
 * <p><b>Transactions:</b> {@code nextValue} (the batch jobs' numbers) runs in a transaction of its own and
 * COMMITS at once. {@code takeNextValue} and {@code lockNextValue} run only inside the caller's transaction
 * (MANDATORY), and {@code peekNextValue} and {@code resetNextValue} join it when there is one and start their own
 * otherwise. A value taken in a transaction that rolls back is given back. The tests call the methods with no
 * transaction of the test around them, or inside {@link Tx#requiringNew()}, so what they assert is committed: every
 * test uses a unique sequence name ({@code IT-SEQ-} + nanoTime), reads committed rows through
 * {@link Tx#requiringNew()}, and best-effort deletes every {@link SequenceEntity} row it created in
 * {@link #cleanup()}. Concurrent callers are in {@code MessageNumberConcurrencyITTest}.
 */
class SequenceServiceITTest extends ITTest {

    @Autowired
    SequenceService sequenceService;

    @PersistenceContext
    EntityManager em;

    /** Every sequence name touched by a test, so it can be removed afterwards. */
    private final List<String> createdNames = new ArrayList<>();

    /** Allocates and tracks a unique sequence name so committed rows never collide or leak. */
    private String uniqueName() {
        String name = "IT-SEQ-" + System.nanoTime();
        createdNames.add(name);
        return name;
    }

    /** Reads the committed SequenceEntity row (or null) in its own new transaction. */
    private SequenceEntity findCommitted(String name) {
        return Tx.requiringNew()
                .call(() -> em.find(SequenceEntity.class, name));
    }

    @AfterEach
    void cleanup() {
        // The calls commit, so explicitly remove the committed rows. Best-effort.
        for (String name : createdNames) {
            try {
                Tx.requiringNew().run(() -> {
                    SequenceEntity e = em.find(SequenceEntity.class, name);
                    if (e != null) {
                        em.remove(e);
                    }
                });
            } catch (Exception ignored) {
                // Swallow: cleanup must never fail the test that produced the data.
            }
        }
        createdNames.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // peekNextValue — read-only, never creates a row
    // ------------------------------------------------------------------------------------------- //

    @Test
    void peekNextValueOnUnknownSequenceReturnsInitialValueAndCreatesNoRow() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        assertNull(findCommitted(name), "precondition: the sequence row must not already exist");

        assertEquals(0L, sequenceService.peekNextValue(seq),
                "peek on an unknown sequence must return its initial value (0)");

        assertNull(findCommitted(name),
                "peekNextValue must NOT create a SequenceEntity row for an unknown sequence");
    }

    @Test
    void peekNextValueOnUnknownSequenceReturnsCustomInitialValue() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 100);

        assertEquals(100L, sequenceService.peekNextValue(seq),
                "peek on an unknown sequence must return the configured initial value (100)");

        assertNull(findCommitted(name),
                "peekNextValue must NOT create a SequenceEntity row even with a custom initial value");
    }

    @Test
    void peekNextValueReturnsCurrentValueWithoutBumping() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        // Prime the sequence: 0 then 1 are handed out, so the next value is now 2.
        assertEquals(0L, sequenceService.nextValue(seq));
        assertEquals(1L, sequenceService.nextValue(seq));
        assertEquals(2L, findCommitted(name).getNextValue(), "row should now point at 2");

        // Two peeks must both observe 2 without advancing the sequence.
        assertEquals(2L, sequenceService.peekNextValue(seq), "first peek must return the current next value");
        assertEquals(2L, sequenceService.peekNextValue(seq), "second peek must be identical — peek does not bump");
        assertEquals(2L, findCommitted(name).getNextValue(),
                "peekNextValue must leave the persisted next value unchanged");

        // The very next allocation must hand out exactly the peeked value.
        assertEquals(2L, sequenceService.nextValue(seq),
                "the next allocation must return the value that peek reported");
    }

    // ------------------------------------------------------------------------------------------- //
    // nextValue — allocates and persists a monotonically increasing value
    // ------------------------------------------------------------------------------------------- //

    @Test
    void nextValueOnUnknownSequenceReturnsInitialValueAndPersistsBumpedRow() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        assertNull(findCommitted(name), "precondition: no row yet");

        assertEquals(0L, sequenceService.nextValue(seq),
                "the first allocation on a fresh sequence must return the initial value (0)");

        SequenceEntity persisted = findCommitted(name);
        assertNotNull(persisted, "nextValue must create the SequenceEntity row");
        assertEquals(name, persisted.getName());
        assertEquals(1L, persisted.getNextValue(),
                "after handing out 0, the persisted next value must be 1");
    }

    @Test
    void nextValueProducesMonotonicGaplessProgression() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        // First call: 0, row -> 1.
        assertEquals(0L, sequenceService.nextValue(seq), "first call returns initial value 0");
        assertEquals(1L, findCommitted(name).getNextValue(), "row advances to 1 after first call");

        // Second call: 1, row -> 2.
        assertEquals(1L, sequenceService.nextValue(seq), "second call returns 1, no gap");
        assertEquals(2L, findCommitted(name).getNextValue(), "row advances to 2 after second call");

        // Third call: 2, row -> 3.
        assertEquals(2L, sequenceService.nextValue(seq), "third call returns 2, monotonic and gapless");
        assertEquals(3L, findCommitted(name).getNextValue(), "row advances to 3 after third call");
    }

    @Test
    void nextValueOnUnknownSequenceHonoursCustomInitialValue() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 100);

        assertEquals(100L, sequenceService.nextValue(seq),
                "the first allocation must return the configured initial value (100)");
        assertEquals(101L, findCommitted(name).getNextValue(),
                "after handing out 100, the persisted next value must be 101");

        // A follow-up allocation continues from the custom base.
        assertEquals(101L, sequenceService.nextValue(seq),
                "the second allocation must continue from the custom initial value");
        assertEquals(102L, findCommitted(name).getNextValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // resetNextValue — resets an existing sequence, or seeds an unknown one
    // ------------------------------------------------------------------------------------------- //

    @Test
    void resetNextValueOnExistingSequenceRewindsToInitialValue() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        // Advance the sequence so the row no longer points at the initial value.
        assertEquals(0L, sequenceService.nextValue(seq));
        assertEquals(1L, sequenceService.nextValue(seq));
        assertEquals(2L, sequenceService.nextValue(seq));
        assertEquals(3L, findCommitted(name).getNextValue(), "row should now point at 3");

        sequenceService.resetNextValue(seq);

        assertEquals(0L, findCommitted(name).getNextValue(),
                "resetNextValue must rewind the persisted next value to the initial value (0)");
        assertEquals(0L, sequenceService.nextValue(seq),
                "after a reset the next allocation must start over from the initial value");
        assertEquals(1L, findCommitted(name).getNextValue(),
                "and the row must advance normally again after the reset");
    }

    @Test
    void resetNextValueOnExistingSequenceRewindsToCustomInitialValue() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 100);

        assertEquals(100L, sequenceService.nextValue(seq));
        assertEquals(101L, sequenceService.nextValue(seq));
        assertEquals(102L, findCommitted(name).getNextValue(), "row should now point at 102");

        sequenceService.resetNextValue(seq);

        assertEquals(100L, findCommitted(name).getNextValue(),
                "resetNextValue must rewind to the custom initial value (100)");
        assertEquals(100L, sequenceService.peekNextValue(seq),
                "peek after reset must report the custom initial value");
    }

    @Test
    void resetNextValueOnUnknownSequenceCreatesRowAtInitialValue() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 100);

        assertNull(findCommitted(name), "precondition: no row yet");

        sequenceService.resetNextValue(seq);

        SequenceEntity persisted = findCommitted(name);
        assertNotNull(persisted, "resetNextValue must create a row for an unknown sequence");
        assertEquals(name, persisted.getName());
        assertEquals(100L, persisted.getNextValue(),
                "the created row must hold the initial value (100), not initial+1");

        assertEquals(100L, sequenceService.peekNextValue(seq),
                "peek must then report the seeded initial value without bumping it");
        assertEquals(100L, findCommitted(name).getNextValue(),
                "and the peek must have left the persisted value untouched");
    }

    @Test
    void resetNextValueJoinsTheCallersTransactionAndRollsBackWithIt() {
        String name = uniqueName();
        assertEquals(0L, sequenceService.nextValue(new DefaultSequence(name, 0)));
        assertEquals(1L, sequenceService.nextValue(new DefaultSequence(name, 0)));

        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            sequenceService.resetNextValue(new DefaultSequence(name, 50));
            assertEquals(50L, sequenceService.peekNextValue(new DefaultSequence(name, 0)),
                    "the caller's transaction sees its own reset");
            throw new IllegalStateException("IT: the caller fails after the reset");
        }));

        assertEquals(2L, findCommitted(name).getNextValue(), "a reset in a rolled-back transaction is undone");
    }

    // ------------------------------------------------------------------------------------------- //
    // takeNextValue — one atomic statement, inside the caller's transaction only
    // ------------------------------------------------------------------------------------------- //

    @Test
    void takeNextValueOutsideATransactionIsRefusedAndChangesNothing() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 0);

        assertThrows(IllegalTransactionStateException.class, () -> sequenceService.takeNextValue(seq),
                "a value is taken only inside the caller's transaction, which can give it back");
        assertNull(findCommitted(name), "the refused call created no row");
    }

    @Test
    void takeNextValueInsideATransactionTakesTheInitialValueThenCountsOn() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 1);

        List<Long> taken = Tx.requiringNew().call(() -> List.of(
                sequenceService.takeNextValue(seq),
                sequenceService.takeNextValue(seq),
                sequenceService.takeNextValue(seq)));

        assertEquals(List.of(1L, 2L, 3L), taken, "the first value is the initial value, then one more each time");
        assertEquals(4L, findCommitted(name).getNextValue(), "the committed row points at the next value");
        assertEquals(4L, sequenceService.peekNextValue(seq), "the peek reports it");
    }

    @Test
    void takeNextValueGoesOnFromAValueSetByAReset() {
        String name = uniqueName();
        Tx.requiringNew().run(() -> sequenceService.takeNextValue(new DefaultSequence(name, 1)));
        sequenceService.resetNextValue(new DefaultSequence(name, 50));

        Sequence seq = new DefaultSequence(name, 1);
        assertEquals(50L, (long) Tx.requiringNew().call(() -> sequenceService.takeNextValue(seq)),
                "a value taken after a reset is the reset value");
        assertEquals(51L, findCommitted(name).getNextValue());
    }

    @Test
    void aValueTakenInATransactionThatRollsBackIsGivenBack() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 1);
        assertEquals(1L, (long) Tx.requiringNew().call(() -> sequenceService.takeNextValue(seq)));

        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            assertEquals(2L, sequenceService.takeNextValue(seq));
            assertEquals(3L, sequenceService.peekNextValue(seq), "the caller's transaction sees its own take");
            throw new IllegalStateException("IT: the publication fails after taking its number");
        }));

        assertEquals(2L, findCommitted(name).getNextValue(), "the rolled-back value is given back");
        assertEquals(2L, (long) Tx.requiringNew().call(() -> sequenceService.takeNextValue(seq)),
                "and the next caller takes it");
    }

    @Test
    void theFirstValueTakenInATransactionThatRollsBackLeavesNoRow() {
        String name = uniqueName();
        Sequence seq = new DefaultSequence(name, 1);

        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            sequenceService.takeNextValue(seq);
            throw new IllegalStateException("IT: the first publication of the year fails");
        }));

        assertNull(findCommitted(name), "the row the take created is rolled back with it");
        assertEquals(1L, (long) Tx.requiringNew().call(() -> sequenceService.takeNextValue(seq)),
                "and the next caller takes the initial value");
    }

    // ------------------------------------------------------------------------------------------- //
    // lockNextValue — locks the row, changes no value
    // ------------------------------------------------------------------------------------------- //

    @Test
    void lockNextValueOutsideATransactionIsRefused() {
        String name = uniqueName();

        assertThrows(IllegalTransactionStateException.class,
                () -> sequenceService.lockNextValue(new DefaultSequence(name, 1)));
        assertNull(findCommitted(name), "the refused call created no row");
    }

    @Test
    void lockNextValueChangesNoValue() {
        String unknown = uniqueName();
        Tx.requiringNew().run(() -> sequenceService.lockNextValue(new DefaultSequence(unknown, 1)));
        assertEquals(1L, findCommitted(unknown).getNextValue(),
                "an unknown sequence is created at its initial value, the value a peek reported for it");

        String known = uniqueName();
        Sequence seq = new DefaultSequence(known, 1);
        Tx.requiringNew().run(() -> {
            sequenceService.takeNextValue(seq);
            sequenceService.takeNextValue(seq);
        });
        Tx.requiringNew().run(() -> {
            sequenceService.lockNextValue(seq);
            assertEquals(3L, sequenceService.takeNextValue(seq), "a take after the lock goes on as before");
        });
        assertEquals(4L, findCommitted(known).getNextValue());
    }
}
