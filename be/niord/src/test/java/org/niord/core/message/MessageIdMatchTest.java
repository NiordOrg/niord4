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
import org.niord.core.message.MessageIdMatch.MatchType;
import org.niord.core.util.UidUtils;
import org.niord.model.message.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link MessageIdMatch}, the small DTO used to encapsulate a
 * (possibly partial) match of a message ID / short ID.
 * <p>
 * Behaviour covered:
 * <ul>
 *   <li>Default constructor + getter/setter round-trip for all four fields
 *       ({@code messageId}, {@code type}, {@code title}, {@code status}).</li>
 *   <li>The 3-arg constructor copies the three supplied values and leaves
 *       {@code status} null.</li>
 *   <li>The 4-arg message-resolving constructor resolves the {@code title} from
 *       the first matching localized description (and {@code status} from the
 *       message) including the edge cases: no descriptions present (title stays
 *       the empty string, not null) and a language mismatch (falls back to the
 *       first available description).</li>
 * </ul>
 * <p>
 * These tests are deliberately framework-neutral: they use only plain JUnit 4
 * and real domain objects built with {@code new} + setters, so they survive a
 * future Quarkus-&gt;Spring port without modification.
 */
public class MessageIdMatchTest {

    /** Builds a Message with a single localized description of the given lang/title. */
    private static Message messageWithDesc(String lang, String title, Status status) {
        Message msg = new Message();
        msg.setStatus(status);
        msg.createDesc(lang).setTitle(title);
        return msg;
    }

    // -------------------------------------------------------------------------
    // Default ctor + getters/setters round-trip
    // -------------------------------------------------------------------------

    @Test
    public void defaultConstructorLeavesAllFieldsNull() {
        MessageIdMatch match = new MessageIdMatch();

        assertNull(match.getMessageId());
        assertNull(match.getType());
        assertNull(match.getTitle());
        assertNull(match.getStatus());
    }

    @Test
    public void settersAndGettersRoundTripEveryField() {
        MessageIdMatch match = new MessageIdMatch();

        match.setMessageId("NW-001-18");
        match.setType(MatchType.SHORT_ID);
        match.setTitle("Round-trip title");
        match.setStatus(Status.VERIFIED);

        assertEquals("NW-001-18", match.getMessageId());
        assertEquals(MatchType.SHORT_ID, match.getType());
        assertEquals("Round-trip title", match.getTitle());
        assertEquals(Status.VERIFIED, match.getStatus());
    }

    @Test
    public void settersAcceptNullValues() {
        MessageIdMatch match = new MessageIdMatch();
        match.setMessageId("x");
        match.setType(MatchType.TEXT);
        match.setTitle("t");
        match.setStatus(Status.DRAFT);

        // Overwriting back to null must be honoured (not ignored)
        match.setMessageId(null);
        match.setType(null);
        match.setTitle(null);
        match.setStatus(null);

        assertNull(match.getMessageId());
        assertNull(match.getType());
        assertNull(match.getTitle());
        assertNull(match.getStatus());
    }

    @Test
    public void allMatchTypesAreSettable() {
        MessageIdMatch match = new MessageIdMatch();

        match.setType(MatchType.UID);
        assertEquals(MatchType.UID, match.getType());

        match.setType(MatchType.SHORT_ID);
        assertEquals(MatchType.SHORT_ID, match.getType());

        match.setType(MatchType.TEXT);
        assertEquals(MatchType.TEXT, match.getType());
    }

    // -------------------------------------------------------------------------
    // 3-arg constructor
    // -------------------------------------------------------------------------

    @Test
    public void threeArgConstructorCopiesValuesAndLeavesStatusNull() {
        String uid = UidUtils.newUid();

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.UID, "Plain title");

        assertEquals(uid, match.getMessageId());
        assertEquals(MatchType.UID, match.getType());
        assertEquals("Plain title", match.getTitle());
        // status is not touched by the 3-arg ctor
        assertNull(match.getStatus());
    }

    @Test
    public void threeArgConstructorPreservesNullTitle() {
        MessageIdMatch match = new MessageIdMatch("free text", MatchType.TEXT, null);

        assertEquals("free text", match.getMessageId());
        assertEquals(MatchType.TEXT, match.getType());
        assertNull(match.getTitle());
        assertNull(match.getStatus());
    }

    // -------------------------------------------------------------------------
    // 4-arg message-resolving constructor - happy path
    // -------------------------------------------------------------------------

    @Test
    public void fourArgConstructorResolvesTitleAndStatusFromMessage() {
        String uid = UidUtils.newUid();
        Message msg = messageWithDesc("en", "Resolved title", Status.PUBLISHED);

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.UID, msg, "en");

        assertEquals(uid, match.getMessageId());
        assertEquals(MatchType.UID, match.getType());
        assertEquals("Resolved title", match.getTitle());
        assertEquals(Status.PUBLISHED, match.getStatus());
        // status must be the same enum constant carried over from the message
        assertSame(msg.getStatus(), match.getStatus());
    }

    // -------------------------------------------------------------------------
    // 4-arg constructor - no descriptions edge case
    // -------------------------------------------------------------------------

    @Test
    public void fourArgConstructorWithNoDescsLeavesTitleEmptyAndCopiesStatus() {
        String uid = UidUtils.newUid();
        Message msg = new Message();
        msg.setStatus(Status.DRAFT);
        // intentionally no descs added

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.SHORT_ID, msg, "en");

        // Title must default to the empty string, NOT null
        assertNotNull(match.getTitle(), "title should never be null even with no descs");
        assertEquals("", match.getTitle());
        assertEquals(Status.DRAFT, match.getStatus());
        assertEquals(uid, match.getMessageId());
        assertEquals(MatchType.SHORT_ID, match.getType());
    }

    // -------------------------------------------------------------------------
    // 4-arg constructor - language mismatch falls back to first available desc
    // -------------------------------------------------------------------------

    @Test
    public void fourArgConstructorFallsBackToFirstDescOnLangMismatch() {
        String uid = UidUtils.newUid();
        // Only a Danish description exists; we request English.
        Message msg = messageWithDesc("da", "DA", Status.CANCELLED);

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.UID, msg, "en");

        // getDescs(filter) returns the first available desc when none match the lang
        assertEquals("DA", match.getTitle());
        assertEquals(Status.CANCELLED, match.getStatus());
    }

    @Test
    public void fourArgConstructorPicksMatchingLangWhenMultipleDescsPresent() {
        String uid = UidUtils.newUid();
        Message msg = new Message();
        msg.setStatus(Status.EXPIRED);
        msg.createDesc("da").setTitle("Dansk titel");
        msg.createDesc("en").setTitle("English title");

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.UID, msg, "en");

        assertEquals("English title", match.getTitle());
        assertEquals(Status.EXPIRED, match.getStatus());
    }

    @Test
    public void fourArgConstructorWithNullLangResolvesAnyDesc() {
        String uid = UidUtils.newUid();
        // A null lang filter includes every language; the first desc is taken.
        Message msg = messageWithDesc("da", "Only Danish", Status.PUBLISHED);

        MessageIdMatch match = new MessageIdMatch(uid, MatchType.TEXT, msg, null);

        assertEquals("Only Danish", match.getTitle());
        assertEquals(Status.PUBLISHED, match.getStatus());
        assertEquals(MatchType.TEXT, match.getType());
    }

    @Test
    public void fourArgConstructorCopiesNullStatusFromMessage() {
        String uid = UidUtils.newUid();
        Message msg = new Message();
        // status left null, no descs
        MessageIdMatch match = new MessageIdMatch(uid, MatchType.UID, msg, "en");

        assertNull(match.getStatus());
        assertEquals("", match.getTitle());
    }
}
