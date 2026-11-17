/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.web;

import io.restassured.response.ValidatableResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mailinglist.MailingList;
import org.niord.core.user.User;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.niord.test.UserTestFactory;
import org.niord.test.http.HttpStub;
import org.niord.test.http.KeycloakAdminStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.niord.core.mail.ScheduledMailRecipient.RecipientType.TO;

/**
 * Deleting a user ({@code DELETE /rest/users/kc-user/{userId}}, Admin > Users & Contacts > Users) also stops Niord's
 * own record of the user from mailing the user (USER1): the user is taken off every mailing list and off the mails
 * queued for the user, and the record's e-mail address is cleared, so that the Manage Recipients drawer no longer
 * offers the user. The record stays, for the history the user made. Niord's part is committed before the Keycloak
 * account is deleted, so a failed delete is repaired by deleting again.
 *
 * <p>The Keycloak admin API is not reachable from the suite, so the real {@link KeycloakIntegrationService} is pointed
 * at a stub for the test, by its {@code authServerUrl} setting field, and pointed back afterwards. The realm the
 * service addresses is the one the suite's token issuer names, {@code niord}, which is the stub's. The users, lists and
 * mails are committed, as the request runs in a transaction of its own, and removed afterwards. The queued mails are
 * due tomorrow, so that the mail sender, which runs every minute, leaves them alone.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class UserDeleteMailingListsITTest extends ITTest {

    private static final String KEYCLOAK_ID = "kc-it-user1-leaver";
    private static final String KEYCLOAK_USER_PATH = "/admin/realms/niord/users/" + KEYCLOAK_ID;
    private static final String LEAVER = "it-user1-leaver";
    private static final String STAYER = "it-user1-stayer";
    private static final String LIST_A = "IT-USER1-A";
    private static final String LIST_B = "IT-USER1-B";

    /** The addresses a mailing list queues its mails to (what {@code User.computeInternetAddress()} gives). */
    private static final String LEAVER_ADDRESS = "Test " + LEAVER + " <" + LEAVER + "@test.niord>";
    private static final String STAYER_ADDRESS = "Test " + STAYER + " <" + STAYER + "@test.niord>";

    /** The mails: queued for the leaver alone, queued for both, sent to the leaver, queued for the stayer alone. */
    private static final String MAIL_TO_LEAVER = "IT-USER1 queued for the leaver";
    private static final String MAIL_TO_BOTH = "IT-USER1 queued for both";
    private static final String MAIL_SENT = "IT-USER1 sent to the leaver";
    private static final String MAIL_TO_STAYER = "IT-USER1 queued for the stayer";

    @Autowired
    KeycloakIntegrationService keycloakIntegrationService;

    @PersistenceContext
    EntityManager em;

    private Object originalAuthServerUrl;

    @BeforeEach
    void createUsersListsAndMails() {
        originalAuthServerUrl = ReflectionTestUtils.getField(keycloakTarget(), "authServerUrl");
        Tx.requiringNew().run(() -> {
            User leaver = UserTestFactory.user(LEAVER);
            User stayer = UserTestFactory.user(STAYER);
            em.persist(leaver);
            em.persist(stayer);
            assertEquals(LEAVER_ADDRESS, leaver.computeInternetAddress());
            em.persist(list(LIST_A, leaver, stayer));
            em.persist(list(LIST_B, leaver));
            em.persist(mail(MAIL_TO_LEAVER, ScheduledMail.Status.PENDING, LEAVER_ADDRESS));
            em.persist(mail(MAIL_TO_BOTH, ScheduledMail.Status.PENDING, LEAVER_ADDRESS, STAYER_ADDRESS));
            em.persist(mail(MAIL_SENT, ScheduledMail.Status.SENT, LEAVER_ADDRESS));
            em.persist(mail(MAIL_TO_STAYER, ScheduledMail.Status.PENDING, STAYER_ADDRESS));
        });
    }

    @AfterEach
    void removeUsersListsAndMails() {
        ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", originalAuthServerUrl);
        Tx.requiringNew().run(() -> {
            em.createQuery("select m from ScheduledMail m where m.subject like 'IT-USER1 %'", ScheduledMail.class)
                    .getResultList()
                    .forEach(em::remove);
            for (String id : List.of(LIST_A, LIST_B)) {
                em.createNamedQuery("MailingList.findByMailingListId", MailingList.class)
                        .setParameter("mailingListId", id)
                        .getResultList()
                        .forEach(em::remove);
            }
            em.flush();
            for (String username : List.of(LEAVER, STAYER)) {
                em.createNamedQuery("User.findByUsername", User.class)
                        .setParameter("username", username)
                        .getResultList()
                        .forEach(em::remove);
            }
        });
    }

    @Test
    void deletingAUserStopsEveryMailToTheUser() throws Exception {
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start().withUser(KEYCLOAK_ID, LEAVER)) {
            pointKeycloakAt(keycloak.baseUrl());

            deleteLeaver().statusCode(204);

            assertEquals(List.of("GET", "DELETE"), keycloakCalls(keycloak.http()),
                    "the account is read for its user name, then deleted");
        }

        assertStoppedMailingTheLeaver();

        // The Manage Recipients drawer offers only users with an address
        given().auth().oauth2(accessToken())
                .when().get("/rest/mailing-lists/mailing-list/" + LIST_B + "/users")
                .then().statusCode(200)
                .body("availableRecipients.username", not(hasItem(LEAVER)))
                .body("availableRecipients.username", hasItem(STAYER));
    }

    @Test
    void whenKeycloakCannotReadTheAccountNothingChanges() throws Exception {
        // A Keycloak that answers 404 to everything: the account cannot be read, so it is not deleted
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start()) {
            pointKeycloakAt(keycloak.baseUrl() + "/nowhere");

            deleteLeaver().statusCode(500);
        }

        Tx.requiringNew().run(() -> {
            assertEquals(List.of(LEAVER, STAYER), recipients(LIST_A));
            assertEquals(List.of(LEAVER), recipients(LIST_B));
            assertEquals(LEAVER + "@test.niord", user(LEAVER).getEmail());
            assertEquals(List.of(LEAVER_ADDRESS), addresses(MAIL_TO_LEAVER));
            assertEquals(List.of(LEAVER_ADDRESS, STAYER_ADDRESS), addresses(MAIL_TO_BOTH));
        });
    }

    @Test
    void aFailedKeycloakDeleteIsRepairedByDeletingAgain() throws Exception {
        // A Keycloak that reads the account but fails its delete. When the delete reaches it, Niord's part is committed.
        AtomicReference<Object> atTheDelete = new AtomicReference<>();
        try (HttpStub keycloak = HttpStub.start()) {
            keycloak.respond(request -> {
                if (!request.path().equals(KEYCLOAK_USER_PATH)) {
                    return HttpStub.StubResponse.status(404);
                }
                if (request.method().equals("GET")) {
                    return HttpStub.StubResponse.json(
                            "{\"id\":\"" + KEYCLOAK_ID + "\",\"username\":\"" + LEAVER + "\"}");
                }
                try {
                    atTheDelete.set(Tx.requiringNew().call(() -> List.of(recipients(LIST_B), user(LEAVER).getEmail() == null)));
                } catch (RuntimeException e) {
                    atTheDelete.set(e);
                }
                return HttpStub.StubResponse.status(500);
            });
            pointKeycloakAt(keycloak.baseUrl());

            deleteLeaver().statusCode(500);

            assertEquals(List.of("GET", "DELETE"), keycloakCalls(keycloak));
            assertEquals(List.of(List.of(), true), atTheDelete.get(),
                    "Niord's part is committed before the account is deleted");
        }
        assertStoppedMailingTheLeaver();

        // The account is still there: deleting again reads it, finds nothing left to do in Niord, and deletes it
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start().withUser(KEYCLOAK_ID, LEAVER)) {
            pointKeycloakAt(keycloak.baseUrl());

            deleteLeaver().statusCode(204);

            assertEquals(List.of("GET", "DELETE"), keycloakCalls(keycloak.http()));
        }
        assertStoppedMailingTheLeaver();
    }

    /** The leaver is off both lists and the queued mails, the sent mail is as it was, and the stayer is untouched. */
    private void assertStoppedMailingTheLeaver() {
        Tx.requiringNew().run(() -> {
            assertEquals(List.of(STAYER), recipients(LIST_A), "the other recipient stays on the list");
            assertEquals(List.of(), recipients(LIST_B));

            assertNull(mailBySubject(MAIL_TO_LEAVER), "a queued mail with no recipient left is removed");
            assertEquals(List.of(STAYER_ADDRESS), addresses(MAIL_TO_BOTH));
            assertEquals(List.of(LEAVER_ADDRESS), addresses(MAIL_SENT), "a sent mail is history");
            assertEquals(List.of(STAYER_ADDRESS), addresses(MAIL_TO_STAYER));

            User leaver = user(LEAVER);
            assertNotNull(leaver, "the record stays, for the history the user made");
            assertNull(leaver.getEmail(), "the record keeps no address to mail");
            assertEquals(STAYER + "@test.niord", user(STAYER).getEmail());
        });
    }

    private ValidatableResponse deleteLeaver() {
        return given().auth().oauth2(accessToken())
                .when().delete("/rest/users/kc-user/" + KEYCLOAK_ID)
                .then();
    }

    /** Points the service object behind the proxy at the given Keycloak. */
    private void pointKeycloakAt(String url) {
        ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", url);
    }

    private KeycloakIntegrationService keycloakTarget() {
        return AopTestUtils.getTargetObject(keycloakIntegrationService);
    }

    /** The methods of the calls on the leaver's account, in order. */
    private static List<String> keycloakCalls(HttpStub keycloak) {
        return keycloak.requests().stream()
                .filter(r -> r.path().equals(KEYCLOAK_USER_PATH))
                .map(HttpStub.RecordedRequest::method)
                .toList();
    }

    private static MailingList list(String mailingListId, User... users) {
        MailingList mailingList = new MailingList();
        mailingList.setMailingListId(mailingListId);
        mailingList.setActive(true);
        mailingList.createDesc("en").setName(mailingListId);
        mailingList.getUsers().addAll(List.of(users));
        return mailingList;
    }

    private static ScheduledMail mail(String subject, ScheduledMail.Status status, String... addresses) throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject(subject);
        mail.setHtmlContents("<p>" + subject + "</p>");
        mail.setStatus(status);
        mail.setSendDate(new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000));
        for (String address : addresses) {
            mail.addRecipient(new ScheduledMailRecipient(TO, address));
        }
        return mail;
    }

    /** The user names of the list's recipients, sorted. */
    private List<String> recipients(String mailingListId) {
        return em.createNamedQuery("MailingList.findByMailingListId", MailingList.class)
                .setParameter("mailingListId", mailingListId)
                .getSingleResult()
                .getUsers().stream()
                .map(User::getUsername)
                .sorted()
                .toList();
    }

    private User user(String username) {
        return em.createNamedQuery("User.findByUsername", User.class)
                .setParameter("username", username)
                .getSingleResult();
    }

    private ScheduledMail mailBySubject(String subject) {
        return em.createQuery("select m from ScheduledMail m where m.subject = :subject", ScheduledMail.class)
                .setParameter("subject", subject)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /** The addresses of the mail's recipients, sorted. */
    private List<String> addresses(String subject) {
        return mailBySubject(subject).getRecipients().stream()
                .map(ScheduledMailRecipient::getAddress)
                .sorted()
                .toList();
    }
}
