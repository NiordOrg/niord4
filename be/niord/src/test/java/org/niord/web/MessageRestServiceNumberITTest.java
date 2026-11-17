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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The number of a warning in an auto-numbered series (YEARLY or CONTINUOUS) is the series' to give, at publication
 * (the review's finding R5 (a), with P1-3). Publication takes a number only when the message has none, so a number an
 * API caller put in a draft's body went out as the warning's number, beside the warning that already had it. The
 * editor never sends one for such a series (the field is editable for a MANUAL series only); a script could.
 *
 * <p>Now the create and update endpoints keep no body number for a draft of an auto-numbered series, and keep the
 * stored number of a warning that has one. A MANUAL or NONE series keeps the body's number, as before.
 *
 * <p>{@code dma-nw} of the seed is a YEARLY series. The drafts are not published, so no number is taken.
 */
class MessageRestServiceNumberITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Test
    void aNumberInTheBodyOfANewDraftOfAnAutoNumberedSeriesIsNotKept() {
        String uid = UidUtils.newUid();
        Map<String, Object> body = body(uid);
        body.put("number", 9999);
        try {
            send(body, false).statusCode(200);
            assertNull(numberOf(uid), "the draft has no number until it is published");
        } finally {
            deleteMessage(uid);
        }
    }

    @Test
    void aNumberInTheBodyOfAnUpdatedDraftOfAnAutoNumberedSeriesIsNotKept() {
        String uid = UidUtils.newUid();
        try {
            send(body(uid), false).statusCode(200);
            Map<String, Object> update = body(uid);
            update.put("number", 9998);
            send(update, true).statusCode(200);
            assertNull(numberOf(uid), "the draft still has no number");
        } finally {
            deleteMessage(uid);
        }
    }

    private io.restassured.response.ValidatableResponse send(Map<String, Object> body, boolean update) {
        var request = given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(body)
                .when();
        return (update ? request.put("/rest/messages/message/" + body.get("id"))
                : request.post("/rest/messages/message")).then();
    }

    /** A minimal draft of the YEARLY series dma-nw, with one DETAILS part and an English title */
    private static Map<String, Object> body(String uid) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", UidUtils.uidToHashedFolderPath("messages", uid));
        msg.put("revision", 0);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", "DRAFT");
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
        titleDesc.put("title", "IT number " + uid);
        msg.put("descs", new ArrayList<>(List.of(titleDesc)));
        return msg;
    }

    private Integer numberOf(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select m.number from Message m where m.uid = :uid", Integer.class)
                        .setParameter("uid", uid).getSingleResult());
    }

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
