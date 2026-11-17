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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deleting an area, a category or a message series that is in use is refused with a 409 whose detail names the
 * uses, and nothing is changed (ERR5). The foreign keys to these rows are NO ACTION: before the check, the delete
 * failed at the flush and answered 500 with no reason. The right path beside each refusal: once the use is removed,
 * the same delete goes through.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class DeleteInUseRefusalsITTest extends RoundTripSupport {

    @Autowired
    RepoStore store;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Last made, first removed
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Areas
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAreaWhoseSubAreaIsInUseIsRefusedWithTheUsesAndDeletedOnceTheyAreGone() {
        String name = "IT-del area " + unique();
        int a = area(name + " A", null);
        int b = area(name + " B", a);
        int c = area(name + " C", b);
        String uid = draftMessage(name, map("areas", list(map("id", c))));
        String domainId = domain(map("areas", list(map("id", b))));

        for (int target : List.of(a, b)) {
            delete("admin", "/rest/areas/area/" + target).statusCode(409)
                    .body("detail", allOf(containsString("1 message"), containsString("the domain " + domainId)));
        }
        assertEquals(List.of(3L, 1L), List.of(
                count("SELECT count(*) FROM public.\"Area\" WHERE id IN (?1, ?2, ?3)", a, b, c),
                count("SELECT count(*) FROM public.\"Message_Area\" WHERE areas_id = ?1", c)),
                "the area, its sub-areas and the message's link remain");
        assertEquals(List.of(b), ints("SELECT id FROM public.\"Area\" WHERE parent_id = ?1", a), "B stays under A");

        // The right path: the uses removed, the same delete removes the area and its subtree
        removeMessage(uid);
        delete("sysadmin", "/rest/domains/domain/" + domainId).statusCode(204);
        assertEquals("true", as("admin").when().delete("/rest/areas/area/" + a).then().statusCode(200)
                .extract().asString().trim());
        assertEquals(0L, count("SELECT count(*) FROM public.\"Area\" WHERE id IN (?1, ?2, ?3)", a, b, c));
    }

    // ------------------------------------------------------------------------------------------- //
    // Categories
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCategoryUsedByAMessageIsRefusedWithTheUsesAndDeletedOnceTheyAreGone() {
        String name = "IT-del category " + unique();
        int x = category(name + " X", null);
        int y = category(name + " Y", x);
        String uid = draftMessage(name, map("categories", list(map("id", y))));

        delete("sysadmin", "/rest/categories/category/" + x).statusCode(409)
                .body("detail", containsString("1 message"));
        assertEquals(List.of(2L, 1L), List.of(
                count("SELECT count(*) FROM public.\"Category\" WHERE id IN (?1, ?2)", x, y),
                count("SELECT count(*) FROM public.\"Message_Category\" WHERE categories_id = ?1", y)),
                "the category, its sub-category and the message's link remain");

        // The right path
        removeMessage(uid);
        assertEquals("true", as("sysadmin").when().delete("/rest/categories/category/" + x).then().statusCode(200)
                .extract().asString().trim());
        assertEquals(0L, count("SELECT count(*) FROM public.\"Category\" WHERE id IN (?1, ?2)", x, y));
    }

    // ------------------------------------------------------------------------------------------- //
    // Message series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageSeriesInADomainIsRefusedWithTheDomainAndDeletedOnceItIsGone() {
        String seriesId = "it-del-series-" + unique();
        post("sysadmin", "/rest/message-series/series", map("seriesId", seriesId, "mainType", "NW",
                "types", list("LOCAL_WARNING"), "shortFormat", "IT-${number}", "numberSequenceType", "YEARLY"));
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));
        String domainId = domain(map("messageSeries", list(map("seriesId", seriesId))));

        delete("sysadmin", "/rest/message-series/series/" + seriesId).statusCode(409)
                .body("detail", containsString("the domain " + domainId));
        assertEquals(1L, count("SELECT count(*) FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1", seriesId),
                "the series remains");

        // The right path
        delete("sysadmin", "/rest/domains/domain/" + domainId).statusCode(204);
        delete("sysadmin", "/rest/message-series/series/" + seriesId).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1", seriesId));
    }

    @Test
    void aMessageSeriesWithAMessageIsRefusedWithTheMessages() {
        String seriesId = "it-del-series-" + unique();
        post("sysadmin", "/rest/message-series/series", map("seriesId", seriesId, "mainType", "NW",
                "types", list("LOCAL_WARNING"), "shortFormat", "IT-${number}", "numberSequenceType", "YEARLY"));
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));
        // A message is written in a series of the editor's domain; it is moved into the new series in SQL, which no
        // domain lists, so the message is the series' only use
        String uid = draftMessage("IT-del series message " + unique(), map());
        execute("UPDATE public.\"Message\" SET \"messageSeries_id\" = (SELECT id FROM public.\"MessageSeries\""
                + " WHERE \"seriesId\" = ?1) WHERE uid = ?2", seriesId, uid);

        delete("sysadmin", "/rest/message-series/series/" + seriesId).statusCode(409)
                .body("detail", containsString("1 message"));
        assertEquals(1L, count("SELECT count(*) FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1", seriesId),
                "the series remains");

        // The right path
        removeMessage(uid);
        delete("sysadmin", "/rest/message-series/series/" + seriesId).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1", seriesId));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private int area(String name, Integer parentId) {
        Map<String, Object> body = map("active", true, "descs", list(map("lang", "en", "name", name)));
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post("admin", "/rest/areas/area", body).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/areas/area/" + id));
        return id;
    }

    private int category(String name, Integer parentId) {
        Map<String, Object> body = map("type", "CATEGORY", "active", true, "descs", list(map("lang", "en", "name", name)));
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post("sysadmin", "/rest/categories/category", body).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/categories/category/" + id));
        return id;
    }

    /** A domain with the given lists, removed at the end */
    private String domain(Map<String, Object> lists) {
        String domainId = "it-del-domain-" + unique();
        Map<String, Object> body = map("domainId", domainId, "name", "IT-del domain", "active", true,
                "sortOrder", 99, "timeZone", "Europe/Copenhagen", "lat", 56.0, "lon", 11.0, "zoomLevel", 6,
                "messageSortOrder", "ID ASC", "publish", false, "atons", false, "templates", false);
        body.putAll(lists);
        post("sysadmin", "/rest/domains/domain", body);
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));
        return domainId;
    }

    /** A draft message in the series dma-nw, with the given fields added or replaced, removed at the end */
    private String draftMessage(String title, Map<String, Object> fields) {
        String uid = UidUtils.newUid();
        String editRepoPath = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        cleanups.add(() -> store.deleteFolder(editRepoPath));
        Map<String, Object> msg = map("id", uid, "repoPath", UidUtils.uidToHashedFolderPath("messages", uid),
                "editRepoPath", editRepoPath, "revision", 0, "mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT",
                "autoTitle", false, "messageSeries", map("seriesId", "dma-nw", "mainType", "NW"),
                "descs", list(map("lang", "en", "title", title)));
        msg.putAll(fields);
        postRaw("editor", "/rest/messages/message", msg).statusCode(200);
        cleanups.add(() -> removeMessage(uid));
        return uid;
    }

    /** Removes a message and its history, as no REST call does: a deleted message keeps its row */
    private void removeMessage(String uid) {
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid").setParameter("uid", uid).executeUpdate();
            em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                    .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
        });
        store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
    }
}
