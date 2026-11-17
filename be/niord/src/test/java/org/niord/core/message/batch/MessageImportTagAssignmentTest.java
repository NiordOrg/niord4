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

package org.niord.core.message.batch;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageTag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AbstractMessageImportWriter#checkAddMessageToTag(Message, MessageTag)} — the
 * pure tag-membership bookkeeping used by message-import batch writers.
 *
 * <p>The method is abstract-class plumbing but carries no dependency on its {@code @Inject} fields, so
 * an anonymous concrete subclass can be instantiated and the {@code protected} method invoked directly
 * from this same package. The tests build bare un-persisted {@link Message} / {@link MessageTag}
 * instances and assert concrete collection state and identity-by-id de-duplication, so they are
 * framework-neutral (no DB, no Quarkus, no mocks) and survive a future Spring port.
 */
public class MessageImportTagAssignmentTest {

    /** Concrete subclass so the abstract writer can be instantiated; {@code @Inject} fields stay null. */
    private final AbstractMessageImportWriter writer = new AbstractMessageImportWriter() {};

    private static Message messageWithId(int id) {
        Message m = new Message();
        m.setId(id);
        return m;
    }

    // --------------------------------------------------------------------------------------------- //
    // null tag
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void nullTagReturnsNullAndDoesNotThrow() {
        Message m = messageWithId(1);

        // A null tag is a valid "no tag configured" case: it must short-circuit to null, not NPE.
        assertNull(writer.checkAddMessageToTag(m, null));

        // The message's own tag list must be left untouched.
        assertTrue(m.getTags().isEmpty(), "message tags must remain empty when no tag is supplied");
    }

    // --------------------------------------------------------------------------------------------- //
    // fresh tag
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void addsMessageToFreshTag() {
        Message m = messageWithId(1);
        MessageTag tag = new MessageTag();

        MessageTag r = writer.checkAddMessageToTag(m, tag);

        // The supplied tag is returned (not a copy).
        assertSame(tag, r);
        // Both sides of the bidirectional association are wired up.
        assertEquals(1, tag.getMessages().size());
        assertTrue(tag.getMessages().contains(m), "tag must contain the message");
        assertTrue(m.getTags().contains(tag), "message must back-reference the tag");
    }

    // --------------------------------------------------------------------------------------------- //
    // idempotency / de-dup by id
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void idempotentForSameMessageId() {
        Message m = messageWithId(1);
        MessageTag tag = new MessageTag();

        writer.checkAddMessageToTag(m, tag);
        // Adding the very same instance again must not duplicate it.
        writer.checkAddMessageToTag(m, tag);
        assertEquals(1, tag.getMessages().size(), "re-adding the same message must not grow the collection");

        // A DIFFERENT instance that shares the same id must also be rejected — matching is by id.
        Message dup = messageWithId(1);
        writer.checkAddMessageToTag(dup, tag);
        assertEquals(1, tag.getMessages().size(), "a distinct instance with a duplicate id must not be added");
        assertFalse(tag.getMessages().contains(dup), "the duplicate-id instance must not have been stored");
        assertTrue(tag.getMessages().contains(m), "the original message must still be the stored one");
        // The rejected duplicate must not have been wired back to the tag either.
        assertTrue(dup.getTags().isEmpty(), "rejected duplicate must not back-reference the tag");
    }

    // --------------------------------------------------------------------------------------------- //
    // distinct ids accumulate
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void addsSecondDistinctMessage() {
        Message m1 = messageWithId(1);
        Message m2 = messageWithId(2);
        MessageTag tag = new MessageTag();

        writer.checkAddMessageToTag(m1, tag);
        writer.checkAddMessageToTag(m2, tag);

        assertEquals(2, tag.getMessages().size(), "distinct ids must both be retained");
        assertTrue(tag.getMessages().contains(m1));
        assertTrue(tag.getMessages().contains(m2));
        assertTrue(m1.getTags().contains(tag));
        assertTrue(m2.getTags().contains(tag));
    }
}
