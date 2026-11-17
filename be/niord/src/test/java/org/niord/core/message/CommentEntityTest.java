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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Comment} entity's pure (non-persistent) logic: the {@code toVo} mappings
 * (core fields, acknowledged-by name resolution, defensive email-address copying and the
 * own-comment ownership flag), the value-object constructor, and the {@code onCreate} timestamp hook.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. {@link User} and
 * {@link Message} instances are built directly and asserted on concrete field values, so the tests
 * survive a future Spring port. Note that {@link Comment#toVo()} dereferences {@code user.getName()},
 * so every comment under test is given a non-null {@code user}.
 */
public class CommentEntityTest {

    /** Builds a {@link User} with the given id and composed name "first last". */
    private static User user(int id, String first, String last) {
        User u = new User();
        u.setId(id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo() — core field mapping
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoMapsCoreFields() {
        User u = new User();
        u.setId(1);
        u.setUsername("jdoe");
        u.setFirstName("Jane");
        u.setLastName("Doe");

        Comment c = new Comment();
        c.setUser(u);
        c.setComment("hello");
        Date d = new Date();
        c.setCreated(d);

        CommentVo vo = c.toVo();

        assertEquals("Jane Doe", vo.getUser());
        assertEquals("hello", vo.getComment());
        assertEquals(d, vo.getCreated());
        assertNull(vo.getAcknowledgedBy(), "no acknowledger set -> acknowledgedBy is null");
        assertNull(vo.getAcknowledgeDate(), "no acknowledge date set -> acknowledgeDate is null");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo() — acknowledgedBy resolves to the acknowledger's composed name
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoMapsAcknowledgedByName() {
        User u = user(1, "Jane", "Doe");

        User ackUser = new User();
        ackUser.setId(2);
        ackUser.setFirstName("Bob");
        ackUser.setLastName("Roe");

        Date d2 = new Date(1_500_000_000_000L);

        Comment c = new Comment();
        c.setUser(u);
        c.setAcknowledgedBy(ackUser);
        c.setAcknowledgeDate(d2);

        CommentVo vo = c.toVo();

        assertEquals("Bob Roe", vo.getAcknowledgedBy());
        assertEquals(d2, vo.getAcknowledgeDate());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo() — email addresses
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoOmitsEmptyEmailAddresses() {
        Comment c = new Comment();
        c.setUser(user(1, "Jane", "Doe"));
        // emailAddresses defaults to an empty list

        CommentVo vo = c.toVo();

        assertNull(vo.getEmailAddresses(),
                "toVo must leave emailAddresses null when the entity list is empty");
    }

    @Test
    public void toVoIncludesNonEmptyEmailAddresses() {
        Comment c = new Comment();
        c.setUser(user(1, "Jane", "Doe"));
        List<String> emails = new ArrayList<>(List.of("a@x.com"));
        c.setEmailAddresses(emails);

        CommentVo vo = c.toVo();

        assertEquals(List.of("a@x.com"), vo.getEmailAddresses());
        // toVo must copy the list defensively, not expose the entity's own collection.
        assertNotSame(emails, vo.getEmailAddresses(),
                "vo email list must be a defensive copy, not the entity's instance");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(User) — ownComment ownership flag
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoWithSameOwnerFlagsOwnComment() {
        Comment c = new Comment();
        c.setUser(user(1, "Jane", "Doe"));

        User caller = new User();
        caller.setId(1);

        assertEquals(Boolean.TRUE, c.toVo(caller).isOwnComment());
    }

    @Test
    public void toVoWithDifferentUserNotOwn() {
        Comment c = new Comment();
        c.setUser(user(1, "Jane", "Doe"));

        User caller = new User();
        caller.setId(2);

        assertEquals(Boolean.FALSE, c.toVo(caller).isOwnComment());
    }

    @Test
    public void toVoWithNullUserNotOwn() {
        Comment c = new Comment();
        c.setUser(user(1, "Jane", "Doe"));

        assertEquals(Boolean.FALSE, c.toVo(null).isOwnComment());
    }

    // --------------------------------------------------------------------------------------------- //
    // Comment(CommentVo, Message, User) constructor
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorFromVoCopiesFields() {
        Message msg = new Message();
        User u = user(1, "Jane", "Doe");

        CommentVo vo = new CommentVo();
        vo.setId(5);
        vo.setComment("body");
        vo.setEmailAddresses(List.of("e@x.com"));

        Comment c = new Comment(vo, msg, u);

        assertEquals(Integer.valueOf(5), c.getId());
        assertSame(msg, c.getMessage());
        assertSame(u, c.getUser());
        assertEquals("body", c.getComment());
        assertEquals(List.of("e@x.com"), c.getEmailAddresses());
    }

    @Test
    public void constructorFromVoWithNullEmailsLeavesEmptyList() {
        Message msg = new Message();
        User u = user(1, "Jane", "Doe");

        CommentVo vo = new CommentVo();
        vo.setEmailAddresses(null);

        Comment c = new Comment(vo, msg, u);

        assertNotNull(c.getEmailAddresses(), "emailAddresses must default to an empty (non-null) list");
        assertTrue(c.getEmailAddresses().isEmpty(), "null VO emails must leave the entity list empty");
    }

    // --------------------------------------------------------------------------------------------- //
    // onCreate() — @PrePersist timestamp back-fill
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void onCreateSetsCreatedWhenNull() {
        Comment c = new Comment();
        assertNull(c.getCreated(), "precondition: created is null before onCreate");

        c.onCreate();

        assertNotNull(c.getCreated(), "onCreate must back-fill a created timestamp when none is set");
    }

    @Test
    public void onCreateKeepsExistingCreated() {
        Comment c = new Comment();
        Date d = new Date(1_400_000_000_000L);
        c.setCreated(d);

        c.onCreate();

        assertEquals(d, c.getCreated(), "onCreate must not overwrite an existing created timestamp");
    }
}
