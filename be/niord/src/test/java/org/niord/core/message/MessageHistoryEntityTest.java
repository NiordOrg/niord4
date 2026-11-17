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

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the {@link MessageHistory} entity's pure (non-persistent) logic: GZIP snapshot
 * compression / round-trip ({@code compressSnapshot} + {@code getSnapshot}), the {@code toVo}
 * value-object mapping (including the {@code user}-null guard and decompression of the snapshot),
 * and the {@code onCreate} {@code @PrePersist} timestamp back-fill hook.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. {@link User} and
 * {@link MessageHistory} instances are built directly and asserted on concrete field / round-trip
 * values, so the tests survive a future Quarkus-to-Spring port. The snapshot is stored GZIP-encoded,
 * so the only stable oracle for the stored bytes is a decompression round-trip rather than a literal
 * byte comparison.
 */
public class MessageHistoryEntityTest {

    /** A small JSON-ish payload used as a snapshot under test. */
    private static final String SNAPSHOT_JSON = "{\"a\":1}";

    /** Builds a {@link User} with the given composed name "first last". */
    private static User user(String first, String last) {
        User u = new User();
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    // --------------------------------------------------------------------------------------------- //
    // compressSnapshot(String) + getSnapshot()
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void compressSnapshotStoresGzipBytesNotRawUtf8() {
        MessageHistory mh = new MessageHistory();
        mh.compressSnapshot(SNAPSHOT_JSON);

        byte[] stored = mh.getSnapshot();
        assertNotNull(stored, "compressSnapshot must store a non-null byte[]");

        byte[] rawUtf8 = SNAPSHOT_JSON.getBytes(StandardCharsets.UTF_8);
        // GZIP output must differ from the plain UTF-8 bytes (it is compressed/framed).
        assertFalse(java.util.Arrays.equals(rawUtf8, stored),
                "stored snapshot must be GZIP-encoded, not the raw UTF-8 bytes");
    }

    @Test
    public void compressSnapshotRoundTripsToOriginalString() {
        MessageHistory mh = new MessageHistory();
        mh.compressSnapshot(SNAPSHOT_JSON);

        String roundTripped = GzipUtils.decompressStringIgnoreError(mh.getSnapshot());
        assertEquals(SNAPSHOT_JSON, roundTripped,
                "decompressing the stored snapshot must yield the original string");
    }

    @Test
    public void compressSnapshotOfEmptyStringRoundTripsToEmpty() {
        MessageHistory mh = new MessageHistory();
        mh.compressSnapshot("");

        assertNotNull(mh.getSnapshot(), "empty string must still compress to a non-null byte[]");
        assertEquals("", GzipUtils.decompressStringIgnoreError(mh.getSnapshot()),
                "empty snapshot must round-trip to the empty string");
    }

    @Test
    public void compressSnapshotOfNullStoresNull() {
        MessageHistory mh = new MessageHistory();
        // Pre-seed with data so we can verify the null branch actually overwrites it.
        mh.compressSnapshot(SNAPSHOT_JSON);
        assertNotNull(mh.getSnapshot());

        mh.compressSnapshot(null);

        assertNull(mh.getSnapshot(), "compressing a null snapshot must store null");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo() — full field mapping
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoMapsAllFields() {
        User u = user("First", "Last");

        MessageHistory mh = new MessageHistory();
        mh.setId(99);
        mh.setStatus(Status.DRAFT);
        mh.setVersion(4);
        mh.setUser(u);
        mh.setCreated(new Date(1000L));
        mh.compressSnapshot(SNAPSHOT_JSON);

        MessageHistoryVo vo = mh.toVo();

        assertEquals(Integer.valueOf(99), vo.getMessageId(),
                "messageId must come from the entity id");
        assertEquals(Status.DRAFT, vo.getStatus());
        assertEquals(4, vo.getVersion());
        assertEquals("First Last", vo.getUser(), "user must be the composed user name");
        assertEquals(new Date(1000L), vo.getCreated());
        assertEquals(SNAPSHOT_JSON, vo.getSnapshot(),
                "snapshot must be decompressed back to the original string");
    }

    @Test
    public void toVoWithoutUserLeavesUserNull() {
        MessageHistory mh = new MessageHistory();
        mh.setId(7);
        mh.setStatus(Status.PUBLISHED);
        // no user set -> toVo guards user != null

        MessageHistoryVo vo = mh.toVo();

        assertNull(vo.getUser(), "toVo must leave user null when the entity has no user");
        // Sanity: the rest of the mapping still happened.
        assertEquals(Integer.valueOf(7), vo.getMessageId());
        assertEquals(Status.PUBLISHED, vo.getStatus());
    }

    @Test
    public void toVoWithNullSnapshotLeavesSnapshotNull() {
        MessageHistory mh = new MessageHistory();
        mh.setStatus(Status.VERIFIED);
        // snapshot left null -> decompressStringIgnoreError(null) returns null

        MessageHistoryVo vo = mh.toVo();

        assertNull(vo.getSnapshot(), "null snapshot must map to a null VO snapshot");
    }

    @Test
    public void toVoWithNullIdMapsNullMessageId() {
        MessageHistory mh = new MessageHistory();
        mh.setStatus(Status.DRAFT);
        // id left null

        MessageHistoryVo vo = mh.toVo();

        assertNull(vo.getMessageId(), "a new (id-less) history must map to a null messageId");
    }

    @Test
    public void toVoPreservesCreatedDateInstance() {
        Date created = new Date(1000L);

        MessageHistory mh = new MessageHistory();
        mh.setStatus(Status.DRAFT);
        mh.setCreated(created);

        MessageHistoryVo vo = mh.toVo();

        assertEquals(created, vo.getCreated());
    }

    // --------------------------------------------------------------------------------------------- //
    // getSnapshot() / setSnapshot() — raw byte[] accessors
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void setSnapshotStoresRawBytesVerbatim() {
        byte[] raw = new byte[] { 1, 2, 3, 4 };

        MessageHistory mh = new MessageHistory();
        mh.setSnapshot(raw);

        assertArrayEquals(raw, mh.getSnapshot(), "setSnapshot must store the byte[] verbatim");
    }

    // --------------------------------------------------------------------------------------------- //
    // onCreate() — @PrePersist timestamp back-fill
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void onCreateSetsCreatedWhenNull() {
        MessageHistory mh = new MessageHistory();
        assertNull(mh.getCreated(), "precondition: created is null before onCreate");

        mh.onCreate();

        assertNotNull(mh.getCreated(), "onCreate must back-fill a created timestamp when none is set");
    }

    @Test
    public void onCreateKeepsExistingCreated() {
        MessageHistory mh = new MessageHistory();
        Date d = new Date(1_400_000_000_000L);
        mh.setCreated(d);

        mh.onCreate();

        assertEquals(d, mh.getCreated(), "onCreate must not overwrite an existing created timestamp");
    }
}
