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

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MessageTagMapper}: pins the projection previously embedded in
 * {@code MessageTag.toVo()} and the {@code MessageTag(MessageTagVo)} conversion constructor.
 */
public class MessageTagMapperTest {

    private final MessageTagMapper mapper = new MessageTagMapper();

    private static MessageTag tag() {
        MessageTag t = new MessageTag();
        t.setTagId("tag-123");
        t.setName("My Tag");
        t.setType(MessageTagType.DOMAIN);
        t.setCreated(new Date(1_000_000L));
        t.setExpiryDate(new Date(2_000_000L));
        t.setLocked(true);
        // messageCount has no setter; the entity exposes it via getMessageCount() (default 0)
        return t;
    }

    @Test
    public void toVoCopiesAllScalarFields() {
        MessageTag t = tag();

        MessageTagVo vo = mapper.toVo(t);

        assertEquals("tag-123", vo.getTagId());
        assertEquals("My Tag", vo.getName());
        assertEquals(MessageTagType.DOMAIN, vo.getType());
        assertEquals(new Date(1_000_000L), vo.getCreated());
        assertEquals(new Date(2_000_000L), vo.getExpiryDate());
        assertTrue(vo.isLocked());
        assertEquals(0, vo.getMessageCount());
    }

    @Test
    public void toVoCopiesMessageCountFromEntity() {
        // updateMessageCount() recomputes messageCount from the messages list size
        MessageTag t = tag();
        t.getMessages().add(new Message());
        t.getMessages().add(new Message());
        t.updateMessageCount();

        MessageTagVo vo = mapper.toVo(t);

        assertEquals(2, vo.getMessageCount());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void toEntityCopiesConstructorFields() {
        MessageTagVo vo = new MessageTagVo();
        vo.setTagId("tag-456");
        vo.setName("Other Tag");
        vo.setType(MessageTagType.PUBLIC);
        vo.setCreated(new Date(5_000_000L));
        vo.setExpiryDate(new Date(9_000_000L));
        vo.setLocked(true);
        vo.setMessageCount(7);

        MessageTag t = mapper.toEntity(vo);

        // Fields copied by the MessageTag(MessageTagVo) conversion constructor
        assertEquals("tag-456", t.getTagId());
        assertEquals("Other Tag", t.getName());
        assertEquals(MessageTagType.PUBLIC, t.getType());
        assertEquals(new Date(9_000_000L), t.getExpiryDate());
        assertTrue(t.isLocked());

        // Fields NOT copied by the conversion constructor remain at entity defaults
        assertNull(t.getCreated(), "created is not copied into the entity");
        assertEquals(0, t.getMessageCount(), "messageCount is not copied into the entity");
    }

    @Test
    public void toEntityLockedDefaultsFalse() {
        MessageTagVo vo = new MessageTagVo();
        vo.setTagId("tag-789");
        vo.setName("Unlocked");
        vo.setType(MessageTagType.PRIVATE);
        // locked left at default false

        MessageTag t = mapper.toEntity(vo);

        assertFalse(t.isLocked());
        assertEquals(MessageTagType.PRIVATE, t.getType());
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    public void updateEntityOverwritesConstructorFieldsInPlace() {
        MessageTag t = tag();

        MessageTagVo vo = new MessageTagVo();
        vo.setTagId("new-id");
        vo.setName("Renamed");
        vo.setType(MessageTagType.TEMP);
        vo.setExpiryDate(new Date(3_000_000L));
        vo.setLocked(false);

        mapper.updateEntity(t, vo);

        assertEquals("new-id", t.getTagId());
        assertEquals("Renamed", t.getName());
        assertEquals(MessageTagType.TEMP, t.getType());
        assertEquals(new Date(3_000_000L), t.getExpiryDate());
        assertFalse(t.isLocked());
    }

    @Test
    public void roundTripPreservesConstructorFields() {
        MessageTag original = tag();

        MessageTagVo vo = mapper.toVo(original);
        MessageTag restored = mapper.toEntity(vo);

        assertEquals(original.getTagId(), restored.getTagId());
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getExpiryDate(), restored.getExpiryDate());
        assertEquals(original.isLocked(), restored.isLocked());
    }
}
