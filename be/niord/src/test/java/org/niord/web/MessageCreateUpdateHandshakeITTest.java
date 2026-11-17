/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.repo.RepositoryService;
import org.niord.core.util.UidUtils;
import org.niord.model.message.AttachmentVo;
import org.niord.test.ITTest;

import org.niord.test.RepoRows;
import org.niord.core.repo.RepoStore;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the happy-path business logic of {@code POST /rest/messages/message} (create) and
 * {@code PUT /rest/messages/message/{id}} (update) — specifically the repository-folder / attachment
 * <em>handshake</em>, which the existing {@link MessageRestServiceITTest} (authorization matrix only) does not cover.
 *
 * <p>Editing a message uses two repository folders: a permanent, hash-based {@code repoPath}
 * ({@code messages/&lt;a&gt;/&lt;ab&gt;/&lt;uid&gt;}) and a throw-away {@code editRepoPath} ({@code temp/&lt;uuid&gt;})
 * the front-end uploads attachments into while editing. On save the REST layer calls
 * {@code SystemMessageVo.toMessageRepo()} (rewriting every embedded/attachment path from the temp folder to the
 * permanent folder) and then {@code MessageService.updateMessageFromTempRepoFolder()} (copying the uploaded files
 * across). These tests assert both halves end-to-end over HTTP, and additionally pin the underlying
 * {@link RepositoryService} folder-sync rules directly against the repository store.
 *
 * <p>Create/update commit on the server, so the created messages and their repository folders are cleaned up
 * afterwards (in a fresh transaction, and the store's rows) to keep the shared seed database pristine.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageCreateUpdateHandshakeITTest extends ITTest {

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // REST create: the attachment moves from the temp edit folder to the permanent message folder
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createMessageMovesAttachmentFromTempToPermanentRepoAndRewritesItsPath() throws Exception {
        String editRepoPath = given().auth().oauth2(accessToken())
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");

        String fileName = "att-" + System.nanoTime() + ".txt";
        byte[] content = ("attachment-body-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        given().auth().oauth2(accessToken())
                .multiPart("file", fileName, content)
                .when().post("/rest/repo/upload-temp/" + editRepoPath)
                .then().statusCode(200);

        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);

        Map<String, Object> msg = baseDraft(uid, repoPath, editRepoPath);
        msg.put("attachments", List.of(new HashMap<>(Map.of(
                "path", editRepoPath + "/" + fileName,
                "fileName", fileName,
                "type", "text/plain"))));

        try {
            String attPath = given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(msg)
                    .when().post("/rest/messages/message")
                    .then().statusCode(200)
                    .body("id", equalTo(uid))
                    .body("status", equalTo("DRAFT"))
                    .extract().path("attachments[0].path");

            // The attachment path is rewritten from the temp folder to the permanent message folder.
            assertTrue(attPath.startsWith("messages/"),
                    "attachment path must be rewritten to the permanent repo folder, was: " + attPath);
            assertFalse(attPath.startsWith("temp/"), "attachment path must no longer point at the temp folder");
            assertTrue(attPath.contains(uid), "attachment path must reference the message uid");

            // The uploaded file was copied to the permanent message repository folder.
            String moved = repoPath + "/" + fileName;
            assertTrue(store.exists(moved),
                    "the uploaded attachment file must be copied to the permanent repo folder: " + moved);

            // The attachment row was persisted against the message with the rewritten path.
            String persistedPath = Tx.requiringNew().call(() ->
                    em.createQuery("select a.path from Attachment a where a.message.uid = :uid", String.class)
                            .setParameter("uid", uid).getResultStream().findFirst().orElse(null));
            assertEquals(attPath, persistedPath, "the persisted attachment path must match the rewritten path");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // REST update: a field change persists
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateMessagePersistsFieldChanges() throws Exception {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath1 = newTempDir();

        // Create the draft first (committed).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(baseDraft(uid, repoPath, editRepoPath1))
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        try {
            // Now update the DETAILS subject via PUT.
            String newSubject = "Updated subject " + System.nanoTime();
            Map<String, Object> update = baseDraft(uid, repoPath, newTempDir());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) update.get("parts");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> descs = (List<Map<String, Object>>) parts.get(0).get("descs");
            descs.get(0).put("subject", newSubject);

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(update)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .body("id", equalTo(uid))
                    .body("status", equalTo("DRAFT"))
                    .body("parts[0].descs[0].subject", equalTo(newSubject));

            // Confirm it persisted (reload via GET).
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .body("parts[0].descs[0].subject", equalTo(newSubject));
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // REST update: the folders of the sync are not the request body's to choose
    //
    // repoPath and editRepoPath both arrive with the body. The copy target is the persisted message's own
    // folder, derived from its UID, whatever the body names, and the copy source must be a folder below the
    // repository's temp root (403 otherwise, and the whole update rolls back).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateMessageSyncsIntoTheUidFolderWhateverRepoPathTheBodyNames() throws Exception {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String foreignRepoPath = "messages/it-foreign-" + System.nanoTime();

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(baseDraft(uid, repoPath, newTempDir()))
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        String editRepoPath = newTempDir();
        String fileName = "att-" + System.nanoTime() + ".txt";
        try {
            RepoRows.writeText(store, editRepoPath + "/" + fileName, "attachment");

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(baseDraft(uid, foreignRepoPath, editRepoPath))
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(200);

            assertTrue(store.exists(repoPath + "/" + fileName),
                    "the file must land in the message's own, UID-derived folder");
            assertFalse(store.hasAny(foreignRepoPath),
                    "the folder the body named must not be created");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(foreignRepoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    @Test
    void updateMessageWithAnEditFolderOutsideTempIsForbiddenAndRollsBack() throws Exception {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(baseDraft(uid, repoPath, newTempDir()))
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        // A committed folder of another message, holding a file: not an edit folder
        String otherFolder = "messages/it-other-" + System.nanoTime();
        try {
            RepoRows.writeText(store, otherFolder + "/other.txt", "belongs to another message");

            Map<String, Object> update = baseDraft(uid, repoPath, otherFolder);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) update.get("parts");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> descs = (List<Map<String, Object>>) parts.get(0).get("descs");
            descs.get(0).put("subject", "Must not persist " + System.nanoTime());

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(update)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(403);

            assertFalse(store.exists(repoPath + "/other.txt"),
                    "nothing may be copied from a folder outside the temp root");
            // The refusal rolls the whole update back
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .body("parts[0].descs[0].subject", equalTo("Subject"));
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(otherFolder);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // REST create/update: the attachments and thumbnail name files in the message's own folder
    //
    // They are stored as the body sends them and resolved against the repository later, by the
    // anonymous message archive export among others. A reference that names anything else is a 400 and
    // nothing is saved.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createMessageWithAnAttachmentFileNameOutsideItsFolderIsRefusedAndNotSaved() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath = newTempDir();

        Map<String, Object> msg = baseDraft(uid, repoPath, editRepoPath);
        msg.put("attachments", List.of(new HashMap<>(Map.of(
                "path", editRepoPath + "/secret.txt",
                "fileName", "../../../../other-message/secret.txt",
                "type", "text/plain"))));
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(msg)
                    .when().post("/rest/messages/message")
                    .then().statusCode(400);

            assertEquals(0L, (long) Tx.requiringNew().call(() ->
                    em.createQuery("select count(m) from Message m where m.uid = :uid", Long.class)
                            .setParameter("uid", uid).getSingleResult()),
                    "a refused message must not be saved");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    @Test
    void updateMessageWithAnAttachmentPathOrThumbnailOutsideItsFolderIsRefused() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(baseDraft(uid, repoPath, newTempDir()))
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        try {
            // An attachment whose path starts with the message's folder and climbs out of it
            Map<String, Object> update = baseDraft(uid, repoPath, newTempDir());
            update.put("attachments", List.of(new HashMap<>(Map.of(
                    "path", repoPath + "/../../../other-message/1/secret.txt",
                    "fileName", "secret.txt",
                    "type", "text/plain"))));
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(update)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(400);

            // A thumbnail in another message's folder
            Map<String, Object> thumbnail = baseDraft(uid, repoPath, newTempDir());
            thumbnail.put("thumbnailPath", "messages/0/00/other-message/custom_thumb_256.png");
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(thumbnail)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(400);

            List<?> attachments = given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .extract().path("attachments");
            assertTrue(attachments == null || attachments.isEmpty(), "the refused attachment must not be saved");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // REST create/update: the short id and the referenced message ids hold no markup character
    //
    // The mail and PDF templates print them into HTML unescaped. A new id with <, >, " or & is a 400 and
    // nothing is saved; the formats in use pass, and an id the stored message already has is kept.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createMessageWithAMarkupCharacterInTheShortIdIsRefusedAndNotSaved() {
        for (String c : new String[] { "<", ">", "\"", "&" }) {
            String uid = UidUtils.newUid();
            Map<String, Object> msg = baseDraft(uid, UidUtils.uidToHashedFolderPath("messages", uid), newTempDir());
            msg.put("shortId", "IT-001" + c + "26");
            try {
                given().auth().oauth2(accessToken())
                        .header("NiordDomain", "niord-nw")
                        .contentType("application/json")
                        .body(msg)
                        .when().post("/rest/messages/message")
                        .then().statusCode(400)
                        .body("detail", equalTo("A message short id cannot contain <, >, \" or &"));

                assertEquals(0L, countMessages(uid), "a refused message must not be saved (" + c + ")");
            } finally {
                deleteMessage(uid);
            }
        }
    }

    @Test
    void createMessageWithAFiringAreaShortIdIsSaved() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        Map<String, Object> msg = baseDraft(uid, repoPath, newTempDir());
        msg.put("shortId", "FA/EK-D-371 2017");
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(msg)
                    .when().post("/rest/messages/message")
                    .then().statusCode(200)
                    .body("shortId", equalTo("FA/EK-D-371 2017"));
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
        }
    }

    @Test
    void createMessageReferencingAMessageIdWithAMarkupCharacterIsRefusedAndNotSaved() {
        String uid = UidUtils.newUid();
        Map<String, Object> msg = baseDraft(uid, UidUtils.uidToHashedFolderPath("messages", uid), newTempDir());
        msg.put("references", new java.util.ArrayList<>(List.of(
                new HashMap<>(Map.of("messageId", "NW-001-26", "type", "REFERENCE")),
                new HashMap<>(Map.of("messageId", "NW-002-26\"><script>alert(1)</script>", "type", "REFERENCE")))));
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(msg)
                    .when().post("/rest/messages/message")
                    .then().statusCode(400)
                    .body("detail", equalTo("A referenced message id cannot contain <, >, \" or &"));

            assertEquals(0L, countMessages(uid), "a refused message must not be saved");
        } finally {
            deleteMessage(uid);
        }
    }

    @Test
    void updateMessageToAShortIdWithAMarkupCharacterIsRefusedAndKeepsTheStoredOne() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        Map<String, Object> msg = baseDraft(uid, repoPath, newTempDir());
        msg.put("shortId", "IT-001-26");
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(msg)
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        try {
            Map<String, Object> update = baseDraft(uid, repoPath, newTempDir());
            update.put("shortId", "IT-001-26 & more");
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(update)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(400);

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .body("shortId", equalTo("IT-001-26"));
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
        }
    }

    @Test
    void updateMessageKeepsAStoredShortIdThatHoldsAMarkupCharacter() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(baseDraft(uid, repoPath, newTempDir()))
                .when().post("/rest/messages/message")
                .then().statusCode(200);

        // A message saved before the rule, with a short id that holds markup characters
        String legacyShortId = "IT-legacy <&> " + System.nanoTime();
        Tx.requiringNew().run(() -> em.createQuery("update Message m set m.shortId = :shortId where m.uid = :uid")
                .setParameter("shortId", legacyShortId).setParameter("uid", uid).executeUpdate());

        try {
            // It stays editable: the unchanged short id is not refused
            String newSubject = "Updated subject " + System.nanoTime();
            Map<String, Object> update = baseDraft(uid, repoPath, newTempDir());
            update.put("shortId", legacyShortId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) update.get("parts");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> descs = (List<Map<String, Object>>) parts.get(0).get("descs");
            descs.get(0).put("subject", newSubject);

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(update)
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .body("shortId", equalTo(legacyShortId))
                    .body("parts[0].descs[0].subject", equalTo(newSubject));
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Service-level: the RepositoryService folder-sync rules that underpin the handshake
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTempEditRepoFolderCopiesExistingRepoAndRewritesAttachmentPaths() throws Exception {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        // Seed an existing permanent repo folder with one file.
        RepoRows.writeText(store, repoPath + "/existing.txt", "existing");

        SystemMessageVo vo = new SystemMessageVo();
        vo.setRepoPath(repoPath);
        AttachmentVo att = new AttachmentVo();
        att.setPath(repoPath + "/existing.txt");
        att.setFileName("existing.txt");
        vo.setAttachments(new java.util.ArrayList<>(List.of(att)));

        String editRepoPath = null;
        try {
            repositoryService.createTempEditRepoFolder(vo, true);
            editRepoPath = vo.getEditRepoPath();

            assertTrue(editRepoPath != null && editRepoPath.startsWith("temp/"),
                    "createTempEditRepoFolder must assign a temp editRepoPath");
            // The existing repo content was copied into the temp folder...
            assertTrue(store.exists(editRepoPath + "/existing.txt"),
                    "existing repo files must be copied to the temp edit folder");
            // ...and the attachment path was rewritten to point at the temp folder.
            assertTrue(vo.getAttachments().get(0).getPath().startsWith(editRepoPath),
                    "attachment paths must be rewritten to the temp edit folder while editing");
        } finally {
            store.deleteFolder(repoPath);
            if (editRepoPath != null) {
                store.deleteFolder(editRepoPath);
            }
        }
    }

    @Test
    void updateRepoFolderFromTempEditFolderSyncsNewFolderThenRevisionSubfolder() throws Exception {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath = newTempDir();

        SystemMessageVo vo = new SystemMessageVo();
        vo.setRepoPath(repoPath);
        vo.setEditRepoPath(editRepoPath);
        vo.setRevision(0);

        try {
            // Case 1 (new message): the permanent folder does not exist yet -> the WHOLE temp folder is copied.
            RepoRows.writeText(store, editRepoPath + "/first.txt", "first");

            repositoryService.updateRepoFolderFromTempEditFolder(vo);
            assertTrue(store.exists(repoPath + "/first.txt"),
                    "for a new message the entire temp folder must be copied to the permanent folder");

            // Case 2 (existing message): only the current revision sub-folder is copied across.
            vo.setRevision(1);
            RepoRows.writeText(store, editRepoPath + "/1/second.txt", "second");

            repositoryService.updateRepoFolderFromTempEditFolder(vo);
            assertTrue(store.exists(repoPath + "/1/second.txt"),
                    "for an existing message the current revision sub-folder must be copied to the permanent folder");
        } finally {
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // helpers
    // ------------------------------------------------------------------------------------------- //

    /** A minimal valid NW draft message payload with a single DETAILS part. */
    private Map<String, Object> baseDraft(String uid, String repoPath, String editRepoPath) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", repoPath);
        msg.put("editRepoPath", editRepoPath);
        msg.put("revision", 0);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        // The series carries its mainType: createMessage re-resolves the series from the DB, but updateMessage
        // validates the requested type against the *incoming* (transient) series, so it must carry the mainType.
        msg.put("messageSeries", new HashMap<>(Map.of("seriesId", "dma-nw", "mainType", "NW")));
        Map<String, Object> desc = new HashMap<>();
        desc.put("lang", "en");
        desc.put("subject", "Subject");
        desc.put("details", "Body");
        Map<String, Object> part = new HashMap<>();
        part.put("type", "DETAILS");
        part.put("descs", new java.util.ArrayList<>(List.of(desc)));
        msg.put("parts", new java.util.ArrayList<>(List.of(part)));
        Map<String, Object> titleDesc = new HashMap<>();
        titleDesc.put("lang", "en");
        titleDesc.put("title", "Handshake title");
        msg.put("descs", new java.util.ArrayList<>(List.of(titleDesc)));
        return msg;
    }


    private String newTempDir() {
        return given().auth().oauth2(accessToken())
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");
    }

    private long countMessages(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from Message m where m.uid = :uid", Long.class)
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
