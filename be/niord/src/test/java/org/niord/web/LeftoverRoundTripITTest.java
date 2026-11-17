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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round trips the earlier round-trip classes left uncovered: a message's attachment captions and reference
 * descriptions in two languages; a message series switched between its numbering kinds and the next number it then
 * gives; and a Password setting saved back as the settings API reads it.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
@SuppressWarnings("unchecked")
class LeftoverRoundTripITTest extends ImportRoundTripSupport {

    @Autowired
    RepoStore store;

    private final List<String> uids = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
        for (String uid : uids) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid").setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                        .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
            });
            store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
        }
        uids.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Attachments and references
    // ------------------------------------------------------------------------------------------- //

    @Test
    void attachmentCaptionsAndReferenceDescriptionsKeepBothLanguagesThroughSaveReloadSave() {
        String uid = UidUtils.newUid();
        String editRepoPath = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        cleanups.add(() -> store.deleteFolder(editRepoPath));
        Map<String, Object> msg = map("id", uid, "repoPath", UidUtils.uidToHashedFolderPath("messages", uid),
                "editRepoPath", editRepoPath, "revision", 0, "mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT",
                "autoTitle", false, "messageSeries", map("seriesId", "dma-nw", "mainType", "NW"),
                "descs", list(map("lang", "en", "title", "IT-lo attachments"), map("lang", "da", "title", "IT-lo vedhæftninger")),
                "references", list(
                        map("messageId", "NW-001-26", "type", "REFERENCE",
                                "descs", list(map("lang", "en", "description", awkward("See also")), map("lang", "da", "description", "Se også"))),
                        map("messageId", "NW-002-26", "type", "CANCELLATION",
                                "descs", list(map("lang", "en", "description", "Cancels"), map("lang", "da", "description", "Annullerer")))));
        postRaw("editor", "/rest/messages/message", msg).statusCode(200);
        uids.add(uid);

        // Two attachments uploaded into the edit folder, as the editor does, and captioned in two languages
        Map<String, Object> edit = editable(uid);
        String folder = edit.get("editRepoPath") + "/" + edit.get("revision");
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (String name : List.of("chart-a.png", "chart-b.pdf")) {
            List<Map<String, Object>> uploaded = given().auth().oauth2(accessToken("editor")).header("NiordDomain", "niord-nw")
                    .multiPart("file", name, ("IT " + name).getBytes(StandardCharsets.UTF_8))
                    .when().post("/rest/messages/attachments/" + folder)
                    .then().statusCode(200).extract().jsonPath().getList("");
            Map<String, Object> att = new LinkedHashMap<>(uploaded.getFirst());
            att.put("display", "BELOW");
            att.put("width", "50%");
            att.put("descs", list(map("lang", "en", "caption", awkward("Caption " + name)), map("lang", "da", "caption", "Billedtekst " + name)));
            attachments.add(att);
        }
        edit.put("attachments", attachments);
        putRaw("editor", "/rest/messages/message/" + uid, edit).statusCode(200);

        Map<String, Object> read = editable(uid);
        assertEquals(List.of(Map.of("en", awkward("Caption chart-a.png"), "da", "Billedtekst chart-a.png"),
                        Map.of("en", awkward("Caption chart-b.pdf"), "da", "Billedtekst chart-b.pdf")),
                listOf(read, "attachments").stream().map(a -> descs(a, "caption")).toList(), "the captions, in order");
        assertEquals(List.of("BELOW", "BELOW"), fieldOf(read, "attachments", "display"));
        assertEquals(List.of(Map.of("en", awkward("See also"), "da", "Se også"), Map.of("en", "Cancels", "da", "Annullerer")),
                listOf(read, "references").stream().map(r -> descs(r, "description")).toList(), "the reference descriptions");

        // An unchanged save: no row added or lost
        Map<String, String> rows = Map.of(
                "attachments", "SELECT count(*) FROM public.\"Attachment\" WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = ?1)",
                "attachment descs", "SELECT count(*) FROM public.\"AttachmentDesc\" WHERE entity_id IN (SELECT id FROM public.\"Attachment\""
                        + " WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = ?1))",
                "references", "SELECT count(*) FROM public.\"Reference\" WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = ?1)",
                "reference descs", "SELECT count(*) FROM public.\"ReferenceDesc\" WHERE entity_id IN (SELECT id FROM public.\"Reference\""
                        + " WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = ?1))");
        Map<String, Long> before = counts(rows, uid);
        assertEquals(Map.of("attachments", 2L, "attachment descs", 4L, "references", 2L, "reference descs", 4L), before);
        putRaw("editor", "/rest/messages/message/" + uid, editable(uid)).statusCode(200);
        assertEquals(before, counts(rows, uid), "the rows of an unchanged save");

        // Changed: the Danish caption of the second, the first attachment removed, the English description of the
        // second reference
        Map<String, Object> change = editable(uid);
        List<Map<String, Object>> atts = listOf(change, "attachments");
        listOf(atts.get(1), "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("caption", "Ny tekst"));
        change.put("attachments", list(atts.get(1)));
        listOf(listOf(change, "references").get(1), "descs").stream().filter(d -> "en".equals(d.get("lang")))
                .forEach(d -> d.put("description", "Cancels, now"));
        putRaw("editor", "/rest/messages/message/" + uid, change).statusCode(200);
        Map<String, Object> after = editable(uid);
        assertEquals(List.of(Map.of("en", awkward("Caption chart-b.pdf"), "da", "Ny tekst")),
                listOf(after, "attachments").stream().map(a -> descs(a, "caption")).toList());
        assertEquals("Cancels, now", descs(listOf(after, "references").get(1), "description").get("en"));
        assertEquals(Map.of("attachments", 1L, "attachment descs", 2L, "references", 2L, "reference descs", 4L), counts(rows, uid));
    }

    private Map<String, Object> editable(String uid) {
        Map<String, Object> msg = getMap("editor", "/rest/messages/editable-message/" + uid);
        String path = (String) msg.get("editRepoPath");
        cleanups.add(() -> store.deleteFolder(path));
        return msg;
    }

    // ------------------------------------------------------------------------------------------- //
    // A message series' numbering kind switched
    // ------------------------------------------------------------------------------------------- //

    /**
     * Yearly at 17 for 2030, switched to Continuous: the next number is the continuous sequence's, 1, not 17; set to
     * 5 and switched back to Yearly, 2030 goes on from 17 again; a Manual series still answers the yearly
     * sequence's number. The two kinds keep two sequences side by side and a switch never carries the number over.
     * Recorded as behaviour: the administrator sees the number the series will give ("Next number"). Compute
     * offers a Continuous series the number after its highest in any year (SERIES1, fixed on main on 2026-11-13).
     */
    @Test
    void aSeriesSwitchedBetweenNumberingKindsGivesTheNumberOfTheKindsOwnSequence_documentsBehavior() {
        String seriesId = "it-lo-series-" + unique();
        Map<String, Object> body = map("seriesId", seriesId, "mainType", "NW", "types", list("LOCAL_WARNING"),
                "shortFormat", "IT-${number-3-digits}-${year-2-digits}", "numberSequenceType", "YEARLY");
        post("sysadmin", "/rest/message-series/series", body);
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));
        cleanups.add(() -> execute("DELETE FROM public.\"Sequence\" WHERE name LIKE ?1", "MESSAGE_SERIES_NUMBER_" + seriesId + "%"));
        String number = "/rest/message-series/series/" + seriesId + "/number/2030";
        as("sysadmin").body(17).when().put(number).then().statusCode(200);

        List<Object> seen = new ArrayList<>();
        for (String kind : List.of("CONTINUOUS", "SET 5", "YEARLY", "MANUAL", "NONE", "CONTINUOUS")) {
            if (kind.startsWith("SET")) {
                as("sysadmin").body(5).when().put(number).then().statusCode(200);
                seen.add("set 5");
                continue;
            }
            Map<String, Object> series = getList("sysadmin", "/rest/message-series/search/" + seriesId).getFirst();
            series.put("numberSequenceType", kind);
            put("sysadmin", "/rest/message-series/series/" + seriesId, series);
            seen.add(kind + " " + as("sysadmin").when().get(number).then().statusCode(200).extract().asString().trim());
        }
        assertEquals(List.of("CONTINUOUS 1", "set 5", "YEARLY 17", "MANUAL 17", "NONE 17", "CONTINUOUS 5"), seen,
                "the kind and the next number for 2030 after each switch");
    }

    // ------------------------------------------------------------------------------------------- //
    // Settings of every type
    // ------------------------------------------------------------------------------------------- //

    /**
     * What the settings API answers for each type, and a Password setting saved back as the API reads it: the read
     * leaves the value out, and {@code PUT /rest/settings/setting/{key}} with that read stores null. The settings
     * editor sends nothing unless the value changed, so the page does not do it (A1's observation); an API client
     * that saves what it read erases the password. Recorded as behaviour.
     */
    @Test
    void aPasswordSettingSavedBackAsReadIsErased_documentsBehavior() {
        List<Map<String, Object>> settings = getList("sysadmin", "/rest/settings/editable-settings");
        Map<String, Object> byType = new LinkedHashMap<>();
        settings.forEach(s -> byType.putIfAbsent(String.valueOf(s.get("type")), s));
        Map<String, Object> password = (Map<String, Object>) byType.get("Password");
        assertTrue(password != null, "the catalogue's editable Password settings (pdfEncryptionPassword, wmsPassword): "
                + byType.keySet());
        String key = (String) password.get("key");
        String stored = strings("SELECT COALESCE(setting_value, '<null>') FROM public.\"Setting\" WHERE setting_key = ?1", key).getFirst();
        cleanups.add(() -> {
            if ("<null>".equals(stored)) {
                execute("UPDATE public.\"Setting\" SET setting_value = NULL WHERE setting_key = ?1", key);
            } else {
                execute("UPDATE public.\"Setting\" SET setting_value = ?1 WHERE setting_key = ?2", stored, key);
            }
        });
        execute("UPDATE public.\"Setting\" SET setting_value = ?1 WHERE setting_key = ?2", "\"it-secret\"", key);

        Map<String, Object> read = getMap("sysadmin", "/rest/settings/setting/" + key);
        assertTrue(read.get("value") == null, "the read leaves the password out: " + read);
        int status = putRaw("sysadmin", "/rest/settings/setting/" + key, read).extract().statusCode();
        String after = strings("SELECT COALESCE(setting_value, '<null>') FROM public.\"Setting\" WHERE setting_key = ?1", key).getFirst();
        assertEquals(List.of(200, "<null>"), List.of(status, after), "the answer and the stored value; the types shown "
                + byType.keySet());
    }
}
