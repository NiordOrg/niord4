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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A message is created over REST as a draft only (the review's finding R1, with the publish gate of P2-1). Its status
 * changes go through the status endpoints, which ask the publish gate, number the message, add it to the recording
 * publications and send the status event.
 *
 * <p>Before the fix the create endpoint stored the body's status as it came: an editor could create a message VERIFIED
 * with a publish date that had passed, which the next minute's auto-publish sweep published without the gate's checks
 * (a title, a ticked mandatory channel); an admin could create one PUBLISHED, CANCELLED or EXPIRED, which never had a
 * number, a recording tag or a status event. Now any status but DRAFT answers 400 and nothing is stored.
 */
class MessageRestServiceCreateStatusITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Test
    void anEditorCannotCreateAVerifiedMessageDueForPublication() {
        String uid = UidUtils.newUid();
        Map<String, Object> body = body(uid, "VERIFIED");
        body.put("publishDateFrom", new Date(System.currentTimeMillis() - 3_600_000L).getTime());
        body.put("descs", new ArrayList<>());
        try {
            post("editor", body)
                    .statusCode(400)
                    .body(containsString("created as a draft"));
            assertEquals(0L, countMessages(uid), "nothing is stored");
        } finally {
            deleteMessage(uid);
        }
    }

    @Test
    void anAdminCannotCreateAMessagePublishedCancelledOrExpired() {
        for (String status : List.of("PUBLISHED", "CANCELLED", "EXPIRED", "DELETED")) {
            String uid = UidUtils.newUid();
            try {
                post("admin", body(uid, status))
                        .statusCode(400)
                        .body(containsString("created as a draft"));
                assertEquals(0L, countMessages(uid), status + ": nothing is stored");
            } finally {
                deleteMessage(uid);
            }
        }
    }

    @Test
    void aDraftIsStillCreated() {
        String uid = UidUtils.newUid();
        try {
            post("editor", body(uid, "DRAFT"))
                    .statusCode(200)
                    .body("status", equalTo("DRAFT"));
            assertEquals(1L, countMessages(uid), "the draft is stored");
        } finally {
            deleteMessage(uid);
        }
    }

    @Test
    void aBodyWithNoStatusKeepsItsOwn400() {
        String uid = UidUtils.newUid();
        Map<String, Object> body = body(uid, null);
        body.remove("status");
        try {
            post("editor", body)
                    .statusCode(400)
                    .body(containsString("A message must have a status"));
            assertEquals(0L, countMessages(uid), "nothing is stored");
        } finally {
            deleteMessage(uid);
        }
    }

    private io.restassured.response.ValidatableResponse post(String role, Map<String, Object> body) {
        return given().auth().oauth2(accessToken(role))
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(body)
                .when().post("/rest/messages/message")
                .then();
    }

    /** A minimal NW message body of the given status, with one DETAILS part and an English title */
    private static Map<String, Object> body(String uid, String status) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", UidUtils.uidToHashedFolderPath("messages", uid));
        msg.put("revision", 0);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", status);
        msg.put("autoTitle", false);
        msg.put("messageSeries", new HashMap<>(Map.of("seriesId", "dma-nw", "mainType", "NW")));
        Map<String, Object> desc = new HashMap<>();
        desc.put("lang", "en");
        desc.put("subject", "Subject");
        desc.put("details", "Body");
        Map<String, Object> part = new HashMap<>();
        part.put("type", "DETAILS");
        part.put("descs", new ArrayList<>(List.of(desc)));
        msg.put("parts", new ArrayList<>(List.of(part)));
        Map<String, Object> titleDesc = new HashMap<>();
        titleDesc.put("lang", "en");
        titleDesc.put("title", "IT create status " + uid);
        msg.put("descs", new ArrayList<>(List.of(titleDesc)));
        return msg;
    }

    private long countMessages(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from Message m where m.uid = :uid", Long.class)
                        .setParameter("uid", uid).getSingleResult());
    }

    /** Removes what an unfixed endpoint stored, with its history */
    private void deleteMessage(String uid) {
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                    .setParameter("uid", uid).executeUpdate();
            Object m = em.createQuery("select m from Message m where m.uid = :uid")
                    .setParameter("uid", uid).getResultStream().findFirst().orElse(null);
            if (m != null) {
                em.remove(m);
            }
        });
    }
}
