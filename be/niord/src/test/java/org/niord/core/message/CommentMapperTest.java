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
import org.niord.core.message.vo.CommentVo;
import org.niord.core.user.User;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CommentMapper}.
 *
 * The mapper is pure conversion logic copied verbatim from the {@link Comment}
 * entity, so it can be exercised with in-memory instances and no Quarkus/Spring
 * context.
 */
public class CommentMapperTest {

    private final CommentMapper mapper = new CommentMapper();

    private User user(Integer id, String firstName, String lastName) {
        User u = new User();
        u.setId(id);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return u;
    }

    private Comment comment() {
        Comment c = new Comment();
        c.setId(42);
        c.setUser(user(7, "Alice", "Author"));
        c.setCreated(new Date(1_000L));
        c.setComment("Hello world");
        return c;
    }

    /** Full toVo mapping with acknowledgement and email addresses populated. */
    @Test
    public void toVoMapsAllFields() {
        Comment c = comment();
        c.setAcknowledgedBy(user(9, "Bob", "Acker"));
        c.setAcknowledgeDate(new Date(2_000L));
        c.setEmailAddresses(new java.util.ArrayList<>(Arrays.asList("a@x.com", "b@x.com")));

        CommentVo vo = mapper.toVo(c);

        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals("Alice Author", vo.getUser());
        assertEquals(new Date(1_000L), vo.getCreated());
        assertEquals("Bob Acker", vo.getAcknowledgedBy());
        assertEquals(new Date(2_000L), vo.getAcknowledgeDate());
        assertEquals("Hello world", vo.getComment());
        assertEquals(Arrays.asList("a@x.com", "b@x.com"), vo.getEmailAddresses());
        // ownComment is only set by the user-aware overload
        assertNull(vo.isOwnComment());
    }

    /** When not acknowledged, acknowledgedBy must be null (the entity's null guard). */
    @Test
    public void toVoLeavesAcknowledgedByNullWhenNotAcknowledged() {
        Comment c = comment();

        CommentVo vo = mapper.toVo(c);

        assertNull(vo.getAcknowledgedBy());
        assertNull(vo.getAcknowledgeDate());
    }

    /** Empty email addresses must leave the VO list null, matching the isEmpty() guard. */
    @Test
    public void toVoLeavesEmailAddressesNullWhenEmpty() {
        Comment c = comment();

        CommentVo vo = mapper.toVo(c);

        assertNull(vo.getEmailAddresses());
    }

    /** The user-aware overload flags ownComment=true when the same user id is the author. */
    @Test
    public void toVoFlagsOwnCommentTrueForAuthor() {
        Comment c = comment(); // author id = 7
        User viewer = user(7, "Alice", "Author");

        CommentVo vo = mapper.toVo(c, viewer);

        assertTrue(vo.isOwnComment());
    }

    /** The user-aware overload flags ownComment=false for a different user id. */
    @Test
    public void toVoFlagsOwnCommentFalseForOtherUser() {
        Comment c = comment(); // author id = 7
        User viewer = user(99, "Other", "Viewer");

        CommentVo vo = mapper.toVo(c, viewer);

        assertFalse(vo.isOwnComment());
    }

    /** A null viewing user must yield ownComment=false. */
    @Test
    public void toVoFlagsOwnCommentFalseForNullUser() {
        Comment c = comment();

        CommentVo vo = mapper.toVo(c, null);

        assertFalse(vo.isOwnComment());
    }

    /** Null entity maps to null VO for both overloads. */
    @Test
    public void toVoOfNullReturnsNull() {
        assertNull(mapper.toVo(null));
        assertNull(mapper.toVo(null, user(1, "X", "Y")));
    }

    /**
     * toEntity mirrors the conversion constructor: id/comment/emails come from the VO,
     * message and user are supplied separately.
     */
    @Test
    public void toEntityCopiesVoFieldsAndSuppliedAssociations() {
        CommentVo vo = new CommentVo();
        vo.setId(5);
        vo.setComment("Round trip");
        vo.setEmailAddresses(Arrays.asList("c@x.com"));
        // The display-name fields on the VO are intentionally ignored by toEntity
        vo.setUser("Should Be Ignored");

        Message message = new Message();
        User user = user(3, "Carol", "Creator");

        Comment c = mapper.toEntity(vo, message, user);

        assertEquals(Integer.valueOf(5), c.getId());
        assertEquals("Round trip", c.getComment());
        assertEquals(Arrays.asList("c@x.com"), c.getEmailAddresses());
        assertEquals(message, c.getMessage());
        assertEquals(user, c.getUser());
    }

    /** A null email list on the VO must leave the entity's (empty) collection untouched. */
    @Test
    public void toEntityWithNullEmailsLeavesCollectionEmpty() {
        CommentVo vo = new CommentVo();
        vo.setId(6);
        vo.setComment("No emails");
        vo.setEmailAddresses(null);

        Comment c = mapper.toEntity(vo, new Message(), user(4, "Dan", "Doe"));

        assertTrue(c.getEmailAddresses().isEmpty());
    }

    /** Null VO maps to null entity. */
    @Test
    public void toEntityOfNullReturnsNull() {
        assertNull(mapper.toEntity(null, new Message(), user(1, "X", "Y")));
    }

    /** Round trip: entity -> VO -> entity preserves the VO-carried fields. */
    @Test
    public void roundTripPreservesVoCarriedFields() {
        Comment original = comment();
        original.setEmailAddresses(new java.util.ArrayList<>(Arrays.asList("rt@x.com")));

        CommentVo vo = mapper.toVo(original);
        Comment rebuilt = mapper.toEntity(vo, original.getMessage(), original.getUser());

        assertEquals(original.getId(), rebuilt.getId());
        assertEquals(original.getComment(), rebuilt.getComment());
        assertEquals(original.getEmailAddresses(), rebuilt.getEmailAddresses());
        assertEquals(original.getUser(), rebuilt.getUser());
    }
}
