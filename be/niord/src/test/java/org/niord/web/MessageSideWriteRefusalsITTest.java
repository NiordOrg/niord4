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

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes whose body is empty or incomplete are client errors (WEB17, the sites in the promulgation, report,
 * mailing-list, NAVTEX transmitter, publication series and legacy publication endpoints): they answer 400 with a
 * detail that names what is missing, and nothing is stored, where they used to fail inside the save or on a null
 * and answer 500. The right path of each still works.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class MessageSideWriteRefusalsITTest extends RoundTripSupport {

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // In reverse, so a list goes before the contact it names
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPromulgationTypeWithoutItsIdServiceOrNameIsRefusedWith400() {
        String types = "SELECT count(*) FROM public.\"PromulgationType\"";
        long before = count(types);
        String typeId = "it-web17-" + unique();

        postRaw("sysadmin", "/rest/promulgations/promulgation-type", map())
                .statusCode(400).body("detail", containsString("type ID"));
        postRaw("sysadmin", "/rest/promulgations/promulgation-type", map("typeId", typeId))
                .statusCode(400).body("detail", containsString("service ID"));
        postRaw("sysadmin", "/rest/promulgations/promulgation-type", map("typeId", typeId, "serviceId", "audio"))
                .statusCode(400).body("detail", containsString("name"));
        putRaw("sysadmin", "/rest/promulgations/promulgation-type/navtex", map("typeId", "navtex"))
                .statusCode(400).body("detail", containsString("name"));
        assertEquals(before, count(types), "nothing was stored");
        assertEquals(List.of("NAVTEX"),
                strings("SELECT name FROM public.\"PromulgationType\" WHERE \"typeId\" = 'navtex'"),
                "the update without a name changed nothing");

        // The right path: a complete type is created
        cleanups.add(() -> delete("sysadmin", "/rest/promulgations/promulgation-type/" + typeId));
        Map<String, Object> created = post("sysadmin", "/rest/promulgations/promulgation-type",
                map("typeId", typeId, "serviceId", "audio", "name", "IT WEB17", "active", false, "priority", 99));
        assertEquals(typeId, created.get("typeId"));
        assertEquals(before + 1, count(types));
    }

    // ------------------------------------------------------------------------------------------- //
    // Generating a promulgation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageWithoutPartsGeneratesAPromulgationWithoutText() {
        // {} used to fail on the missing list of parts with a 500. A message without parts holds no text,
        // as the audio service already read it, so every service generates an empty promulgation
        for (String typeId : List.of("navtex", "audio")) {
            postRaw("editor", "/rest/promulgations/generate/" + typeId, map())
                    .statusCode(200).body("type.typeId", is(typeId));
        }
        // A part without descriptions holds no text either
        postRaw("editor", "/rest/promulgations/generate/navtex", map("parts", list(map("type", "DETAILS"))))
                .statusCode(200).body("type.typeId", is("navtex"));
        // The right path: a part with an English text gives the NAVTEX text
        postRaw("editor", "/rest/promulgations/generate/navtex", map("parts", list(map("type", "DETAILS",
                "descs", list(map("lang", "en", "details", "<p>Buoy missing</p>"))))))
                .statusCode(200).body("text", containsString("BUOY"));
    }

    @Test
    void aMessageThatCannotBeReadIsRefusedWith400() {
        postRaw("editor", "/rest/promulgations/generate/navtex", map("parts", list(map("type", "DETAILS",
                "descs", list(map("details", "<p>No language</p>"))))))
                .statusCode(400).body("detail", containsString("language"));
        List<Object> partsWithAHole = new ArrayList<>();
        partsWithAHole.add(null);
        postRaw("editor", "/rest/promulgations/generate/navtex", map("parts", partsWithAHole))
                .statusCode(400).body("detail", containsString("part"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Message reports
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aReportWithoutItsIdNameOrTemplateIsRefusedWith400() {
        String reports = "SELECT count(*) FROM public.\"FmReport\"";
        long before = count(reports);
        String reportId = "it-web17-" + unique();

        postRaw("sysadmin", "/rest/message-reports/report", map())
                .statusCode(400).body("detail", containsString("report ID"));
        postRaw("sysadmin", "/rest/message-reports/report", map("reportId", reportId))
                .statusCode(400).body("detail", containsString("name"));
        postRaw("sysadmin", "/rest/message-reports/report", map("reportId", reportId, "name", "IT WEB17"))
                .statusCode(400).body("detail", containsString("template path"));
        putRaw("sysadmin", "/rest/message-reports/report/standard", map("reportId", "standard"))
                .statusCode(400).body("detail", containsString("name"));
        assertEquals(before, count(reports), "nothing was stored");
        assertEquals(List.of("Standard"),
                strings("SELECT name FROM public.\"FmReport\" WHERE \"reportId\" = 'standard'"),
                "the update without a name changed nothing");

        // The right path: a complete report is created
        cleanups.add(() -> delete("sysadmin", "/rest/message-reports/report/" + reportId));
        Map<String, Object> created = post("sysadmin", "/rest/message-reports/report", map("reportId", reportId,
                "name", "IT WEB17", "templatePath", "/templates/messages/message-list-pdf.ftl"));
        assertEquals(reportId, created.get("reportId"));
        assertEquals(before + 1, count(reports));
    }

    // ------------------------------------------------------------------------------------------- //
    // Mailing lists
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMailingListWithoutItsIdIsRefusedWith400() {
        String lists = "SELECT count(*) FROM public.\"MailingList\"";
        long before = count(lists);
        postRaw("sysadmin", "/rest/mailing-lists/mailing-list", map())
                .statusCode(400).body("detail", containsString("mailing list ID"));
        postRaw("sysadmin", "/rest/mailing-lists/mailing-list", map("mailingListId", " ", "active", false))
                .statusCode(400).body("detail", containsString("mailing list ID"));
        assertEquals(before, count(lists), "nothing was stored");
    }

    @Test
    void aTestOfATriggerThatIsNotThereIsRefusedWith400() {
        String listId = "it-web17-" + unique();
        Map<String, Object> list = scheduledList(listId);
        String path = "/rest/mailing-lists/test-execute-trigger";

        postRaw("sysadmin", path, list).statusCode(400).body("detail", containsString("triggerIndex"));
        postRaw("sysadmin", path + "?triggerIndex=1", list).statusCode(400).body("detail", containsString("0 to 0"));
        postRaw("sysadmin", path + "?triggerIndex=-1", list).statusCode(400).body("detail", containsString("0 to 0"));
        postRaw("sysadmin", path + "?triggerIndex=0", map("mailingListId", listId, "triggers", list()))
                .statusCode(400).body("detail", containsString("no trigger"));
        Map<String, Object> untyped = new LinkedHashMap<>(trigger());
        untyped.remove("type");
        postRaw("sysadmin", path + "?triggerIndex=0", map("mailingListId", listId, "triggers", list(untyped)))
                .statusCode(400).body("detail", containsString("type"));
    }

    @Test
    void aTriggerOfASavedAndOfAnUnsavedListIsTestedAndNothingIsSent() {
        String mails = "SELECT count(*) FROM public.\"ScheduledMail\"";
        long before = count(mails);
        String path = "/rest/mailing-lists/test-execute-trigger?triggerIndex=0";

        // A saved list with a contact: the test renders the contact's mail
        String saved = savedListWithAContact();
        Response savedAnswer = as("sysadmin").body(scheduledList(saved)).when().post(path);
        assertEquals(200, savedAnswer.statusCode(), savedAnswer.asString());
        assertEquals(1, savedAnswer.jsonPath().getList("").size(), "the saved list's contact gets the mail");

        // A list that is not saved yet (the Add drawer's test button before the first save) used to fail on the
        // missing stored list with a 500; it has no recipients yet, so the test renders no mail
        String unsaved = "it-web17-" + unique();
        Response unsavedAnswer = as("sysadmin").body(scheduledList(unsaved)).when().post(path);
        assertEquals(200, unsavedAnswer.statusCode(), unsavedAnswer.asString());
        assertTrue(unsavedAnswer.jsonPath().getList("").isEmpty(), "an unsaved list has no recipients");
        assertEquals(0, count("SELECT count(*) FROM public.\"MailingList\" WHERE \"mailingListId\" = ?1", unsaved),
                "the test saved no list");

        assertEquals(before, count(mails), "a test of a trigger never sends or stores a mail");
    }

    private Map<String, Object> trigger() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "SCHEDULED");
        trigger.put("scheduleType", "DAILY");
        trigger.put("scheduledExecutionTime", "08:00");
        trigger.put("scheduledExecutionTimeZone", "Europe/Copenhagen");
        trigger.put("messageQuery", "domain=niord-nw&status=PUBLISHED");
        trigger.put("scriptResourcePaths", List.of("templates/mailinglist/active-navwarns.ftl"));
        trigger.put("descs", List.of(Map.of("lang", "en", "subject", "IT WEB17")));
        return trigger;
    }

    private Map<String, Object> scheduledList(String listId) {
        return map("mailingListId", listId, "active", false,
                "descs", list(map("lang", "en", "name", "IT WEB17 list")), "triggers", list(trigger()));
    }

    private String savedListWithAContact() {
        Map<String, Object> contact = post("sysadmin", "/rest/contacts/contact",
                map("email", "it-web17-" + unique() + "@example.org", "name", "IT WEB17", "language", "en"));
        cleanups.add(() -> delete("sysadmin", "/rest/contacts/contact/" + contact.get("id")));
        String listId = "it-web17-" + unique();
        post("sysadmin", "/rest/mailing-lists/mailing-list", map("mailingListId", listId, "active", false,
                "descs", list(map("lang", "en", "name", "IT WEB17 list")), "triggers", list()));
        cleanups.add(() -> delete("sysadmin", "/rest/mailing-lists/mailing-list/" + listId));
        put("sysadmin", "/rest/mailing-lists/mailing-list/" + listId + "/contacts", List.of(contact));
        return listId;
    }

    // ------------------------------------------------------------------------------------------- //
    // NAVTEX transmitters
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aTransmitterWithoutItsTypeOrNameIsRefusedWith400() {
        String transmitters = "SELECT count(*) FROM public.\"NavtexTransmitter\"";
        long before = count(transmitters);
        String base = "/rest/promulgation/navtex/transmitters/navtex/transmitter";
        String name = "it-web17-" + unique();

        postRaw("sysadmin", base, map()).statusCode(400).body("detail", containsString("promulgation type"));
        putRaw("sysadmin", base + "/" + name, map("name", name))
                .statusCode(400).body("detail", containsString("promulgation type"));
        postRaw("sysadmin", base, map("promulgationType", map("typeId", "navtex")))
                .statusCode(400).body("detail", containsString("name"));
        assertEquals(before, count(transmitters), "nothing was stored");

        // The right path: a complete transmitter is created
        cleanups.add(() -> delete("sysadmin", base + "/" + name));
        Map<String, Object> created = post("sysadmin", base,
                map("promulgationType", map("typeId", "navtex"), "name", name, "active", false, "areas", list()));
        assertEquals(name, created.get("name"));
        assertEquals(before + 1, count(transmitters));
    }

    // ------------------------------------------------------------------------------------------- //
    // Publication series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSeriesSaveWithoutItsRequiredFieldsIsRefusedAndChangesNothing() {
        String seriesId = "it-web17-" + unique();
        Map<String, Object> body = map("seriesId", seriesId, "categoryId", "dk-dma-publications",
                "domainId", "niord-nw", "contentMode", "NONE", "cadence", "NONE", "numberingScheme", "NONE",
                "nextIssueCreation", "MANUAL", "languages", list("en"),
                "descs", list(map("lang", "en", "name", "IT WEB17 series")));
        String series = "/rest/publication-series/series";

        // A create without the three columns the save copies as they come is refused too
        Map<String, Object> incomplete = new LinkedHashMap<>(body);
        incomplete.remove("cadence");
        incomplete.remove("numberingScheme");
        Response refusedCreate = as("admin").body(incomplete).when().post(series);
        assertEquals(400, refusedCreate.statusCode(), refusedCreate.asString());
        assertEquals("SERIES_INVALID", refusedCreate.jsonPath().getString("code"));
        assertTrue(refusedCreate.asString().contains("cadence, numberingScheme are required"), refusedCreate.asString());
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeries\" WHERE \"seriesId\" = ?1", seriesId));

        // The right path: the complete body is created
        Map<String, Object> created = post("admin", series, body);
        cleanups.add(() -> {
            Object version = getMap("admin", "/rest/publication-series/editable-series/" + seriesId).get("version");
            delete("admin", series + "/" + seriesId + "?version=" + version);
        });

        // PUT {} used to fail at the flush on the NOT NULL cadence with a 500
        Response refusedEmpty = as("admin").body(map()).when().put(series + "/" + seriesId);
        assertEquals(400, refusedEmpty.statusCode(), refusedEmpty.asString());
        assertEquals("SERIES_INVALID", refusedEmpty.jsonPath().getString("code"));
        assertTrue(refusedEmpty.asString().contains("contentMode, cadence, numberingScheme are required"),
                refusedEmpty.asString());

        Map<String, Object> withoutCadence = getMap("admin", "/rest/publication-series/editable-series/" + seriesId);
        withoutCadence.remove("cadence");
        Response refusedSave = as("admin").body(withoutCadence).when().put(series + "/" + seriesId);
        assertEquals(400, refusedSave.statusCode(), refusedSave.asString());
        assertTrue(refusedSave.asString().contains("cadence is required"), refusedSave.asString());
        assertEquals(List.of("NONE"),
                strings("SELECT cadence FROM public.\"PublicationSeries\" WHERE \"seriesId\" = ?1", seriesId),
                "the refused saves changed nothing");

        // The right path: the read-back is saved unchanged
        Map<String, Object> readBack = getMap("admin", "/rest/publication-series/editable-series/" + seriesId);
        Map<String, Object> saved = put("admin", series + "/" + seriesId, readBack);
        assertEquals(created.get("seriesId"), saved.get("seriesId"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Legacy publications
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aLegacyPublicationWithoutItsRequiredFieldsIsRefusedWith400() {
        String publications = "SELECT count(*) FROM public.\"Publication\"";
        long before = count(publications);
        postRaw("admin", "/rest/publications/publication", map())
                .statusCode(400).body("detail", containsString("category"));
        postRaw("admin", "/rest/publications/publication", map("category", map()))
                .statusCode(400).body("detail", containsString("category"));

        // The body's main type, type and status replace the entity's defaults as they come, and all three
        // columns are NOT NULL: a body that names its category but leaves one of them out failed at the flush
        Map<String, Object> category = map("categoryId", "dk-dma-publications");
        postRaw("admin", "/rest/publications/publication", map("category", category))
                .statusCode(400).body("detail", containsString("main type"));
        postRaw("admin", "/rest/publications/publication",
                map("category", category, "type", "LINK", "status", "DRAFT"))
                .statusCode(400).body("detail", containsString("main type"));
        postRaw("admin", "/rest/publications/publication",
                map("category", category, "mainType", "PUBLICATION", "status", "DRAFT"))
                .statusCode(400).body("detail", containsString("its type"));
        postRaw("admin", "/rest/publications/publication",
                map("category", category, "mainType", "PUBLICATION", "type", "LINK"))
                .statusCode(400).body("detail", containsString("its status"));
        assertEquals(before, count(publications), "nothing was stored");
    }

    @Test
    void aPublicationReportWithoutItsParametersIsRefusedWith400() {
        // Without a query string the report used to be called with "?null". The folder is checked first
        // (PublicationRestReleaseITTest: a folder outside the temp root answers 403 whatever the query)
        postRaw("admin", "/rest/publications/generate-publication-report/temp/it-web17-" + unique(), map())
                .statusCode(400).body("detail", containsString("report parameters"));
    }
}
