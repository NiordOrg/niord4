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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.MessageTagVo;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.util.UidUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MessageTag} entity's pure logic: the value-object constructor and
 * {@code toVo} round-trip, the {@code checkAssignTagId} UUID back-fill, the {@code updateMessageCount}
 * persistence hook (tagId/name back-fill plus message-count derivation) and the case-insensitive
 * {@link Comparable#compareTo} ordering.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. They build the
 * {@code messages} list with bare {@code new Message()} instances purely to size the collection (no
 * persistence is performed) and assert concrete field values, so they survive a future Spring port.
 */
public class MessageTagEntityTest {

    /** Builds a list of N un-persisted {@link Message} instances used only to size the collection. */
    private static List<Message> messages(int n) {
        List<Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Message());
        }
        return list;
    }

    // --------------------------------------------------------------------------------------------- //
    // MessageTag(MessageTagVo) constructor
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorCopiesTagIdNameTypeExpiryAndLocked() {
        Date expiry = new Date(1_600_000_000_000L);
        MessageTagVo vo = new MessageTagVo();
        vo.setTagId("ctor-tag-1");
        vo.setName("Ctor Name");
        vo.setType(MessageTagType.PUBLIC);
        vo.setExpiryDate(expiry);
        vo.setLocked(true);

        MessageTag tag = new MessageTag(vo);

        assertEquals("ctor-tag-1", tag.getTagId());
        assertEquals("Ctor Name", tag.getName());
        assertEquals(MessageTagType.PUBLIC, tag.getType());
        assertEquals(expiry, tag.getExpiryDate());
        assertTrue(tag.isLocked());
    }

    @Test
    public void constructorDoesNotCopyCreatedOrMessageCount() {
        MessageTagVo vo = new MessageTagVo();
        vo.setTagId("ctor-tag-2");
        vo.setName("Ctor Name");
        vo.setType(MessageTagType.PRIVATE);
        // These two fields are present on the VO but deliberately NOT read by the constructor.
        vo.setCreated(new Date(1_500_000_000_000L));
        vo.setMessageCount(42);

        MessageTag tag = new MessageTag(vo);

        assertNull(tag.getCreated(), "created must not be copied by the constructor");
        assertEquals(0, tag.getMessageCount(), "messageCount must not be copied by the constructor");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoCopiesAllSevenFields() {
        Date created = new Date(1_400_000_000_000L);
        Date expiry = new Date(1_700_000_000_000L);

        MessageTag tag = new MessageTag();
        tag.setTagId("vo-tag-1");
        tag.setName("Vo Name");
        tag.setCreated(created);                 // inherited from VersionedEntity
        tag.setExpiryDate(expiry);
        tag.setType(MessageTagType.DOMAIN);
        tag.setLocked(true);
        tag.setMessages(messages(3));            // drives messageCount via updateMessageCount
        tag.updateMessageCount();                // populate messageCount before converting

        MessageTagVo vo = tag.toVo();

        assertEquals("vo-tag-1", vo.getTagId());
        assertEquals("Vo Name", vo.getName());
        assertEquals(created, vo.getCreated());
        assertEquals(expiry, vo.getExpiryDate());
        assertEquals(MessageTagType.DOMAIN, vo.getType());
        assertTrue(vo.isLocked());
        assertEquals(3, vo.getMessageCount());
    }

    @Test
    public void toVoReadsCreatedFromInheritedGetter() {
        Date created = new Date(1_234_567_890_000L);
        MessageTag tag = new MessageTag();
        tag.setName("Created Probe");
        tag.setType(MessageTagType.TEMP);
        tag.setCreated(created);

        assertEquals(created, tag.toVo().getCreated());
    }

    // --------------------------------------------------------------------------------------------- //
    // checkAssignTagId
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void checkAssignTagIdAssignsUuidWhenNull() {
        MessageTag tag = new MessageTag();
        assertNull(tag.getTagId(), "precondition: no tagId yet");

        MessageTag returned = tag.checkAssignTagId();

        assertTrue(UidUtils.validUidFormat(tag.getTagId()),
                "must assign a non-blank UUID-format id");
        assertEquals(tag, returned, "must return 'this' for fluent chaining");
    }

    @Test
    public void checkAssignTagIdAssignsUuidWhenBlank() {
        MessageTag tag = new MessageTag();
        tag.setTagId("   ");

        tag.checkAssignTagId();

        assertTrue(UidUtils.validUidFormat(tag.getTagId()),
                "blank tagId must be replaced by a UUID-format id");
    }

    @Test
    public void checkAssignTagIdLeavesExistingIdUntouched() {
        MessageTag tag = new MessageTag();
        tag.setTagId("already-set-tag");

        MessageTag returned = tag.checkAssignTagId();

        assertEquals("already-set-tag", tag.getTagId(), "an existing tagId must be left unchanged");
        assertEquals(tag, returned);
    }

    // --------------------------------------------------------------------------------------------- //
    // updateMessageCount  (@PrePersist / @PreUpdate hook)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateMessageCountSetsCountToMessagesSize() {
        MessageTag tag = new MessageTag();
        tag.setTagId("count-tag-1");
        tag.setName("Count Name");
        tag.setMessages(messages(5));

        tag.updateMessageCount();

        assertEquals(5, tag.getMessageCount());
    }

    @Test
    public void updateMessageCountIsZeroForEmptyMessageList() {
        MessageTag tag = new MessageTag();
        tag.setTagId("count-tag-empty");
        tag.setName("Empty Name");
        // messages defaults to an empty list

        tag.updateMessageCount();

        assertEquals(0, tag.getMessageCount());
    }

    @Test
    public void updateMessageCountBackfillsBlankTagId() {
        MessageTag tag = new MessageTag();
        tag.setName("Has Name");
        tag.setMessages(messages(2));
        assertNull(tag.getTagId(), "precondition: no tagId");

        tag.updateMessageCount();

        assertTrue(UidUtils.validUidFormat(tag.getTagId()),
                "blank tagId must be back-filled with a UUID via checkAssignTagId");
        assertEquals(2, tag.getMessageCount());
    }

    @Test
    public void updateMessageCountBackfillsBlankName() {
        MessageTag tag = new MessageTag();
        tag.setTagId("name-backfill-tag");
        // name left blank

        tag.updateMessageCount();

        assertTrue(tag.getName() != null && !tag.getName().trim().isEmpty(),
                "blank name must be back-filled with a non-blank UUID");
        assertTrue(UidUtils.validUidFormat(tag.getName()),
                "the back-filled name must be UUID-format");
        // The existing tagId must be preserved (only blank ones are backfilled).
        assertEquals("name-backfill-tag", tag.getTagId());
    }

    // --------------------------------------------------------------------------------------------- //
    // compareTo — case-insensitive name ordering
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void compareToReturnsMinusOneForNullArgument() {
        MessageTag tag = new MessageTag();
        tag.setName("any");

        assertEquals(-1, tag.compareTo(null));
    }

    @Test
    public void compareToIsCaseInsensitive() {
        MessageTag lower = new MessageTag();
        lower.setName("apple");
        MessageTag upperSame = new MessageTag();
        upperSame.setName("APPLE");

        // Names differing only in case compare as equal.
        assertEquals(0, lower.compareTo(upperSame));

        MessageTag apple = new MessageTag();
        apple.setName("apple");
        MessageTag banana = new MessageTag();
        banana.setName("BANANA");

        // 'a' < 'b' irrespective of case on either side.
        assertTrue(apple.compareTo(banana) < 0, "'apple' must sort before 'BANANA'");
        assertTrue(banana.compareTo(apple) > 0, "'banana' must sort after 'APPLE'");
    }
}
