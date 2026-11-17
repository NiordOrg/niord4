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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.user.User;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CommentService}, exercising create/read/update/acknowledge of message
 * {@link Comment} rows against the seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>No {@code Comment}, {@code Message} or test-owned {@code User} rows are seeded, so each test
 * creates its own data with globally-unique tokens. All writes are wrapped in {@link TestTransaction}
 * and rolled back so the shared seed database stays pristine and the tests remain independent.
 *
 * <p>Persisted state is asserted by reloading entities through {@code em.flush(); em.clear();
 * em.find(...)} so the assertions verify what actually landed in the database rather than the
 * in-memory state of the service return value.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class CommentServiceITTest extends ITTest {

    @Autowired
    CommentService commentService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Persists a minimal DRAFT NW message and returns the managed entity. */
    private Message persistMessage() {
        Message m = new Message();
        String uid = UUID.randomUUID().toString();
        m.setUid(uid);
        m.setRepoPath("messages/it/" + uid);
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        m.setStatus(Status.DRAFT);
        em.persist(m);
        em.flush();
        return m;
    }

    /** Persists a user with a globally-unique username and returns the managed entity. */
    private User persistUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setFirstName("F");
        u.setLastName("L");
        em.persist(u);
        em.flush();
        return u;
    }

    private String username() {
        return "it-user-" + UUID.randomUUID();
    }

    /** Builds a detached comment template tied to the given message and user. */
    private Comment newComment(Message message, User user, String text) {
        Comment c = new Comment();
        c.setMessage(message);
        c.setUser(user);
        c.setComment(text);
        return c;
    }

    /** A Date offset a number of minutes from now, used to control the created order. */
    private Date minutesFromNow(int minutes) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, minutes);
        return cal.getTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // createComment
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createPersistsAndLinksToMessageAndSetsCreated() {
        Message message = persistMessage();
        User user = persistUser(username());

        Comment created = commentService.createComment(newComment(message, user, "hello world"));
        Integer id = created.getId();
        assertNotNull(id, "createComment must assign a generated primary key");
        assertNotNull(created.getCreated(), "@PrePersist must stamp the created timestamp");

        em.flush();
        em.clear();

        Comment reloaded = em.find(Comment.class, id);
        assertNotNull(reloaded, "the comment row must exist in the database");
        assertEquals("hello world", reloaded.getComment());
        assertEquals(user.getId(), reloaded.getUser().getId());
        assertEquals(message.getUid(), reloaded.getMessage().getUid());
        assertNotNull(reloaded.getCreated(), "the persisted comment must have a created timestamp");

        // The comment must be linked into the owning message's comment collection.
        Message reloadedMessage = em.find(Message.class, message.getId());
        assertTrue(reloadedMessage.getComments().stream().anyMatch(c -> id.equals(c.getId())),
                "the message must hold the newly created comment");
    }

    @Test
    @Transactional
    void createNullMessageThrows() {
        User user = persistUser(username());
        Comment c = newComment(null, user, "no message");

        assertThrows(IllegalArgumentException.class, () -> commentService.createComment(c));
    }

    @Test
    @Transactional
    void createNullUserThrows() {
        Message message = persistMessage();
        Comment c = newComment(message, null, "no user");

        assertThrows(IllegalArgumentException.class, () -> commentService.createComment(c));
    }

    // ------------------------------------------------------------------------------------------- //
    // getComments
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getCommentsReturnsMessageCommentsNewestFirst() {
        Message message = persistMessage();
        User user = persistUser(username());

        // Three comments with explicit, strictly increasing created timestamps.
        Comment first = newComment(message, user, "first");
        first.setCreated(minutesFromNow(-30));
        commentService.createComment(first);

        Comment second = newComment(message, user, "second");
        second.setCreated(minutesFromNow(-20));
        commentService.createComment(second);

        Comment third = newComment(message, user, "third");
        third.setCreated(minutesFromNow(-10));
        commentService.createComment(third);

        em.flush();
        em.clear();

        List<Comment> comments = commentService.getComments(message.getUid());
        assertEquals(3, comments.size(), "all three comments of the message must be returned");
        // Named query orders by created DESC, so the newest (third) is first and the oldest (first) is last.
        assertEquals("third", comments.get(0).getComment(), "newest comment must be first");
        assertEquals("second", comments.get(1).getComment());
        assertEquals("first", comments.get(2).getComment(), "oldest comment must be last");
    }

    @Test
    @Transactional
    void getCommentsEmptyForUnknownUid() {
        assertTrue(commentService.getComments("no-such-uid-" + UUID.randomUUID()).isEmpty(),
                "an unknown message UID must yield an empty comment list");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateComment
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateChangesTextClearsAckAndReplacesEmails() {
        Message message = persistMessage();
        User author = persistUser(username());
        User acknowledger = persistUser(username());

        Comment original = newComment(message, author, "original text");
        original.getEmailAddresses().add("a@x.com");
        commentService.createComment(original);
        Integer id = original.getId();

        // Acknowledge it so that the update has a non-null ack state to clear.
        commentService.acknowledgeComment(acknowledger, id);
        em.flush();
        em.clear();

        // Precondition: the reloaded comment is acknowledged and carries the original email.
        Comment beforeUpdate = em.find(Comment.class, id);
        assertNotNull(beforeUpdate.getAcknowledgeDate(), "precondition: comment must be acknowledged");
        assertNotNull(beforeUpdate.getAcknowledgedBy(), "precondition: comment must have an acknowledger");
        assertEquals(List.of("a@x.com"), beforeUpdate.getEmailAddresses());

        Comment template = new Comment();
        template.setId(id);
        template.setComment("edited");
        template.setEmailAddresses(new java.util.ArrayList<>(List.of("b@x.com", "c@x.com")));
        commentService.updateComment(template);

        em.flush();
        em.clear();

        Comment reloaded = em.find(Comment.class, id);
        assertEquals("edited", reloaded.getComment(), "update must persist the new comment text");
        assertNull(reloaded.getAcknowledgeDate(), "update must clear the acknowledge date");
        assertNull(reloaded.getAcknowledgedBy(), "update must clear the acknowledger");
        // The email addresses are a JPA @ElementCollection with no @OrderColumn, so the reload order
        // is not guaranteed by the persistence layer; compare order-independently as a Set.
        assertEquals(Set.of("b@x.com", "c@x.com"), new HashSet<>(reloaded.getEmailAddresses()),
                "update must replace the email addresses with the supplied ones");
    }

    @Test
    @Transactional
    void updateUnknownIdThrows() {
        Comment u = new Comment();
        u.setId(777777);
        u.setComment("x");

        assertThrows(IllegalArgumentException.class, () -> commentService.updateComment(u));
    }

    // ------------------------------------------------------------------------------------------- //
    // acknowledgeComment
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void acknowledgeSetsUserAndDate() {
        Message message = persistMessage();
        User author = persistUser(username());

        Comment comment = newComment(message, author, "please ack");
        commentService.createComment(comment);
        Integer id = comment.getId();

        User ack = persistUser(username());
        Comment result = commentService.acknowledgeComment(ack, id);
        assertEquals(ack.getId(), result.getAcknowledgedBy().getId(),
                "the returned comment must record the acknowledging user");

        em.flush();
        em.clear();

        Comment reloaded = em.find(Comment.class, id);
        assertNotNull(reloaded.getAcknowledgedBy(), "the persisted comment must have an acknowledger");
        assertEquals(ack.getId(), reloaded.getAcknowledgedBy().getId(),
                "the persisted acknowledger must be the supplied user");
        assertNotNull(reloaded.getAcknowledgeDate(), "the persisted comment must have an acknowledge date");
    }

    @Test
    @Transactional
    void acknowledgeNullUserThrows() {
        Message message = persistMessage();
        User author = persistUser(username());

        Comment comment = newComment(message, author, "please ack");
        commentService.createComment(comment);
        Integer id = comment.getId();

        assertThrows(IllegalArgumentException.class,
                () -> commentService.acknowledgeComment(null, id));
    }

    @Test
    @Transactional
    void acknowledgeUnknownIdThrows() {
        User user = persistUser(username());

        assertThrows(IllegalArgumentException.class,
                () -> commentService.acknowledgeComment(user, 666666));
    }

    // ------------------------------------------------------------------------------------------- //
    // createComment - client-supplied id (SECAUD-14)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A create body must not carry an id: {@code Comment(CommentVo, Message, User)} copies it and
     * {@code saveEntity()} merges a persisted entity, so a plain {@code user} could have used the create
     * endpoint to rewrite another user's comment - reassigning its author and clearing its acknowledgement -
     * bypassing the SECAUD-5 author check that guards the update endpoint.
     */
    @Test
    @Transactional
    void createCommentRejectsCommentWithExistingId() {
        Message message = persistMessage();
        User author = persistUser(username());
        Comment victim = commentService.createComment(newComment(message, author, "victim comment"));
        em.flush();
        assertNotNull(victim.getId(), "precondition: the victim comment must have a generated id");

        User attacker = persistUser(username());
        Comment forged = newComment(message, attacker, "hijacked");
        forged.setId(victim.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> commentService.createComment(forged));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

}
