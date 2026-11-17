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
import org.niord.core.message.vo.MessageHistoryVo;
import org.niord.core.user.User;
import org.niord.core.util.GzipUtils;
import org.niord.model.message.Status;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link MessageHistoryMapper}: pins the projection previously embedded in
 * {@code MessageHistory.toVo()}.
 */
public class MessageHistoryMapperTest {

    private final MessageHistoryMapper mapper = new MessageHistoryMapper();

    private static MessageHistory history() {
        MessageHistory h = new MessageHistory();
        h.setId(42);
        h.setStatus(Status.PUBLISHED);
        h.setVersion(7);
        h.setCreated(new Date(1_500_000_000_000L));
        h.compressSnapshot("{\"id\":\"msg-1\"}");
        return h;
    }

    @Test
    public void toVoCopiesAllScalarFields() {
        MessageHistoryVo vo = mapper.toVo(history());

        // messageId is deliberately sourced from the history's OWN id (matches the entity's toVo())
        assertEquals(Integer.valueOf(42), vo.getMessageId());
        assertEquals(Status.PUBLISHED, vo.getStatus());
        assertEquals(7, vo.getVersion());
        assertEquals(new Date(1_500_000_000_000L), vo.getCreated());
        // snapshot is stored GZIP-compressed and exposed decompressed
        assertEquals("{\"id\":\"msg-1\"}", vo.getSnapshot());
    }

    @Test
    public void toVoCopiesUserNameWhenUserPresent() {
        User user = new User();
        user.setFirstName("Jens");
        user.setLastName("Hansen");

        MessageHistory h = history();
        h.setUser(user);

        MessageHistoryVo vo = mapper.toVo(h);

        assertEquals("Jens Hansen", vo.getUser());
    }

    @Test
    public void toVoLeavesUserNullWhenUserAbsent() {
        MessageHistory h = history();
        h.setUser(null);

        MessageHistoryVo vo = mapper.toVo(h);

        assertNull(vo.getUser());
    }

    @Test
    public void toVoLeavesSnapshotNullWhenNoSnapshot() {
        MessageHistory h = history();
        h.setSnapshot(null);

        MessageHistoryVo vo = mapper.toVo(h);

        assertNull(vo.getSnapshot());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void toEntityCopiesScalarFieldsAndCompressesSnapshot() {
        MessageHistoryVo vo = new MessageHistoryVo();
        vo.setStatus(Status.DRAFT);
        vo.setVersion(3);
        Date created = new Date(1_400_000_000_000L);
        vo.setCreated(created);
        vo.setSnapshot("{\"id\":\"msg-2\"}");

        MessageHistory h = mapper.toEntity(vo);

        assertEquals(Status.DRAFT, h.getStatus());
        assertEquals(3, h.getVersion());
        assertEquals(created, h.getCreated());
        // the entity stores the snapshot GZIP-compressed
        assertArrayEquals(GzipUtils.compressStringIgnoreError("{\"id\":\"msg-2\"}"), h.getSnapshot());
    }

    @Test
    public void roundTripPreservesSnapshotStatusAndVersion() {
        MessageHistory entity = mapper.toEntity(mapper.toVo(history()));

        assertEquals(Status.PUBLISHED, entity.getStatus());
        assertEquals(7, entity.getVersion());
        assertEquals(new Date(1_500_000_000_000L), entity.getCreated());
        assertEquals("{\"id\":\"msg-1\"}", GzipUtils.decompressStringIgnoreError(entity.getSnapshot()));
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
