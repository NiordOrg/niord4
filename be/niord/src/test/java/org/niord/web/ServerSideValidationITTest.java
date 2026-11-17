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
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.test.http.KeycloakAdminStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend refuses what the administration forms refuse (DOMAIN2, DOMAIN4, and ERR4's blank contact address): a
 * domain without a domain id or a name (refused before Keycloak is asked to make the domain's client), a domain
 * time zone that {@code ZoneId} and {@code TimeZone.getTimeZone} do not both read, a negative next message number,
 * an area, category or source created with no name, and a contact whose address is blank or one the contact form's
 * e-mail rule refuses. Each answered 200 and stored the value: an unknown zone fell back to GMT in every printed time
 * of the domain, and a negative sequence numbered published warnings from it. Each is now a 400 naming the reason,
 * and nothing is stored. The domain import skips a row with such a zone and says so in its log, and the rest of the
 * file is imported. The right path beside each refusal still answers 200: an address the form accepts is stored, and
 * a contact stored earlier with an address the form refuses can still be edited while its address is unchanged.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class ServerSideValidationITTest extends ImportRoundTripSupport {

    private final List<Runnable> cleanups = new ArrayList<>();

    @Autowired
    KeycloakIntegrationService keycloakIntegrationService;

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
    // Domain time zone
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aDomainSavedWithAnUnknownTimeZoneIsRefusedAndKeepsItsZone() {
        String domainId = "it-val-domain-" + unique();
        Map<String, Object> body = domainBody(domainId, "Europe/Copenhagen");
        post("sysadmin", "/rest/domains/domain", body);
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));

        body.put("timeZone", "Mars/Olympus");
        putRaw("sysadmin", "/rest/domains/domain/" + domainId, body).statusCode(400)
                .body("detail", containsString("Mars/Olympus"));
        assertEquals(List.of("Europe/Copenhagen"), storedZone(domainId), "the stored zone is unchanged");

        // An offset id ZoneId reads but TimeZone.getTimeZone turns into GMT is refused too
        body.put("timeZone", "+01:00");
        putRaw("sysadmin", "/rest/domains/domain/" + domainId, body).statusCode(400)
                .body("detail", containsString("+01:00"));
        assertEquals(List.of("Europe/Copenhagen"), storedZone(domainId), "the stored zone is unchanged");

        // The right path: a zone both read is stored, an offset zone TimeZone reads, and GMT itself
        body.put("timeZone", "America/Nuuk");
        put("sysadmin", "/rest/domains/domain/" + domainId, body);
        assertEquals(List.of("America/Nuuk"), storedZone(domainId));
        body.put("timeZone", "GMT+01:00");
        put("sysadmin", "/rest/domains/domain/" + domainId, body);
        assertEquals(List.of("GMT+01:00"), storedZone(domainId));
        body.put("timeZone", "UTC");
        put("sysadmin", "/rest/domains/domain/" + domainId, body);
        assertEquals(List.of("UTC"), storedZone(domainId));

        // A blank zone stays allowed, as before (the domain then prints in the server's zone)
        body.put("timeZone", "");
        put("sysadmin", "/rest/domains/domain/" + domainId, body);
    }

    @Test
    void aDomainCreatedWithAnUnknownTimeZoneIsRefusedAndNotStored() {
        String domainId = "it-val-domain-" + unique();
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));

        // No ZoneId reads the first; ZoneId reads the offset ids, but TimeZone.getTimeZone, which every print of the
        // domain's times uses, turns them into GMT; the padded one is read as GMT by TimeZone as well
        for (String zone : List.of("Mars/Olympus", "+01:00", "UTC+01:00", "UT+01:00", " Europe/Copenhagen")) {
            postRaw("sysadmin", "/rest/domains/domain", domainBody(domainId, zone)).statusCode(400)
                    .body("detail", containsString(zone));
        }
        assertEquals(0L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId));
    }

    @Test
    void aDomainImportSkipsTheRowWithAnUnknownTimeZoneAndImportsTheRest() {
        String bad = "it-val-imp-bad-" + unique();
        String offset = "it-val-imp-offset-" + unique();
        String good = "it-val-imp-good-" + unique();
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + bad));
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + offset));
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + good));

        JobEnd job = importRows("sysadmin", "/rest/domains/upload-domains", "domain-import",
                List.of(domainBody(bad, "Mars/Olympus"), domainBody(offset, "+01:00"),
                        domainBody(good, "Europe/Copenhagen")));

        assertEquals("COMPLETED", job.status(), job.log());
        assertTrue(job.log().contains("Skipping domain " + bad + ": unknown time zone 'Mars/Olympus'"), job.log());
        assertTrue(job.log().contains("Skipping domain " + offset + ": unknown time zone '+01:00'"), job.log());
        assertEquals(0L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = ?1", bad));
        assertEquals(0L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = ?1", offset));
        assertEquals(List.of("Europe/Copenhagen"), storedZone(good), "the other row is imported");
    }

    @Test
    void aDomainWithoutAnIdOrANameIsRefusedBeforeKeycloakIsAsked() throws Exception {
        // DOMAIN4: the create made the domain's Keycloak client first and failed at the commit, a 500
        String domainId = "it-val-nameless-" + unique();
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));
        Object original = ReflectionTestUtils.getField(keycloakTarget(), "authServerUrl");
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start().withClient("niord-nw")) {
            ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", keycloak.baseUrl());

            Map<String, Object> nameless = domainBody(domainId, "Europe/Copenhagen");
            nameless.remove("name");
            postRaw("sysadmin", "/rest/domains/domain", nameless).statusCode(400)
                    .body("detail", is("A domain needs a name"));
            nameless.put("name", "  ");
            postRaw("sysadmin", "/rest/domains/domain", nameless).statusCode(400)
                    .body("detail", is("A domain needs a name"));
            postRaw("sysadmin", "/rest/domains/domain", map()).statusCode(400)
                    .body("detail", is("A domain needs a domain id"));
            Map<String, Object> idless = domainBody(" ", "Europe/Copenhagen");
            postRaw("sysadmin", "/rest/domains/domain", idless).statusCode(400)
                    .body("detail", is("A domain needs a domain id"));

            assertEquals(List.of(), keycloak.http().requests(), "Keycloak is not asked");
            assertNull(keycloak.clientUuid(domainId), "no Keycloak client is made");
            assertEquals(0L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId));

            // The right path: with a name the domain is made, and so is its client
            post("sysadmin", "/rest/domains/domain", domainBody(domainId, "Europe/Copenhagen"));
            assertNotNull(keycloak.clientUuid(domainId), "the domain's Keycloak client");
        } finally {
            ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", original);
        }

        // An update that drops the name is refused too, and the stored name stays
        Map<String, Object> renamed = domainBody(domainId, "Europe/Copenhagen");
        renamed.put("name", "");
        putRaw("sysadmin", "/rest/domains/domain/" + domainId, renamed).statusCode(400)
                .body("detail", is("A domain needs a name"));
        assertEquals(List.of("IT-val domain"),
                strings("SELECT name FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId));
    }

    @Test
    void aDomainTheDatabaseRefusesLeavesNoKeycloakClient() throws Exception {
        // DOMAIN4 (review): the insert waited for the commit, after Keycloak had made the domain's client, so any
        // other refusal of the database (here a name longer than its column; the form sets no limit) still answered
        // 500 with the client left behind. The insert now reaches the database before Keycloak is asked.
        String domainId = "it-val-long-" + unique();
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));
        Object original = ReflectionTestUtils.getField(keycloakTarget(), "authServerUrl");
        try (KeycloakAdminStub keycloak = KeycloakAdminStub.start().withClient("niord-nw")) {
            ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", keycloak.baseUrl());

            Map<String, Object> body = domainBody(domainId, "Europe/Copenhagen");
            body.put("name", "N".repeat(256));
            int status = postRaw("sysadmin", "/rest/domains/domain", body).extract().statusCode();
            assertTrue(status >= 400, "the create is refused, it answered " + status);

            assertEquals(List.of(), keycloak.http().requests(), "Keycloak is not asked");
            assertNull(keycloak.clientUuid(domainId), "no Keycloak client is made");
            assertEquals(0L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId));

            // The right path: a name the column holds makes the domain and its client
            body.put("name", "N".repeat(255));
            post("sysadmin", "/rest/domains/domain", body);
            assertNotNull(keycloak.clientUuid(domainId), "the domain's Keycloak client");
        } finally {
            ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", original);
        }
    }

    private KeycloakIntegrationService keycloakTarget() {
        return AopTestUtils.getTargetObject(keycloakIntegrationService);
    }

    private static Map<String, Object> domainBody(String domainId, String zone) {
        return map("domainId", domainId, "name", "IT-val domain", "active", false,
                "sortOrder", 99, "timeZone", zone, "lat", 56.0, "lon", 11.0, "zoomLevel", 6,
                "messageSortOrder", "ID ASC", "publish", false, "atons", false, "templates", false);
    }

    private List<String> storedZone(String domainId) {
        return strings("SELECT \"timeZone\" FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId);
    }

    // ------------------------------------------------------------------------------------------- //
    // Next message number
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aNegativeNextNumberIsRefusedAndTheSequenceIsUnchanged() {
        // A year no other test sets, of a yearly series
        String path = "/rest/message-series/series/dma-nw/number/2096";
        putRaw("sysadmin", path, "7").statusCode(200);
        cleanups.add(() -> execute("DELETE FROM public.\"Sequence\" WHERE name = ?1", "MESSAGE_SERIES_NUMBER_dma-nw_2096"));

        putRaw("sysadmin", path, "-5").statusCode(400).body("detail", containsString("-5"));
        assertEquals(List.of(7L), nextValue(), "the sequence row is unchanged");

        // A body of JSON null names no number
        as("sysadmin").body("null").when().put(path).then().statusCode(400);
        assertEquals(List.of(7L), nextValue());

        // The right path: 0 is the popover's own lower bound
        putRaw("sysadmin", path, "0").statusCode(200);
        assertEquals(List.of(0L), nextValue());
    }

    private List<Long> nextValue() {
        return column("SELECT \"nextValue\" FROM public.\"Sequence\" WHERE name = ?1", "MESSAGE_SERIES_NUMBER_dma-nw_2096")
                .stream().map(v -> ((Number) v).longValue()).toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Names
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAreaWithoutANameIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"Area\"");
        postRaw("admin", "/rest/areas/area", map()).statusCode(400)
                .body("detail", containsString("An area needs a name"));
        postRaw("admin", "/rest/areas/area", map("descs", list(map("lang", "en", "name", "  ")))).statusCode(400);
        assertEquals(before, count("SELECT count(*) FROM public.\"Area\""), "no area row");

        // The right path: a name in one language is enough
        int id = ((Number) post("admin", "/rest/areas/area",
                map("descs", list(map("lang", "da", "name", "IT-val area " + unique())))).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/areas/area/" + id));
    }

    @Test
    void aCategoryWithoutANameIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"Category\"");
        postRaw("sysadmin", "/rest/categories/category", map()).statusCode(400)
                .body("detail", containsString("A category needs a name"));
        postRaw("sysadmin", "/rest/categories/category", map("descs", list(map("lang", "en", "name", "")))).statusCode(400);
        assertEquals(before, count("SELECT count(*) FROM public.\"Category\""), "no category row");

        int id = ((Number) post("sysadmin", "/rest/categories/category",
                map("type", "CATEGORY", "descs", list(map("lang", "en", "name", "IT-val category " + unique())))).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/categories/category/" + id));
    }

    @Test
    void aSourceWithoutANameIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"Source\"");
        postRaw("admin", "/rest/sources/source", map()).statusCode(400)
                .body("detail", containsString("A source needs a name"));
        postRaw("admin", "/rest/sources/source", map("active", true,
                "descs", list(map("lang", "en", "abbreviation", "ITV")))).statusCode(400);
        assertEquals(before, count("SELECT count(*) FROM public.\"Source\""), "no source row");

        int id = ((Number) post("admin", "/rest/sources/source", map("active", true,
                "descs", list(map("lang", "en", "name", "IT-val source " + unique(), "abbreviation", "ITV")))).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/sources/source/" + id));
    }

    // ------------------------------------------------------------------------------------------- //
    // Contact e-mail
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aContactWithoutAValidAddressIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"Contact\"");
        postRaw("admin", "/rest/contacts/contact", map("email", "not-an-address", "name", "IT-val")).statusCode(400)
                .body("detail", allOf(containsString("not-an-address"), containsString("not an e-mail address")));
        postRaw("admin", "/rest/contacts/contact", map()).statusCode(400)
                .body("detail", containsString("e-mail address"));
        postRaw("admin", "/rest/contacts/contact", map("email", "  ")).statusCode(400);
        assertEquals(before, count("SELECT count(*) FROM public.\"Contact\""), "no contact row");

        // The right path, then an update to an invalid or blank address is refused and changes nothing
        String email = "it-val-" + unique() + "@example.org";
        int id = ((Number) post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-val")).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/contacts/contact/" + id));

        putRaw("admin", "/rest/contacts/contact/" + id, map("id", id, "email", "not-an-address", "name", "IT-val"))
                .statusCode(400).body("detail", containsString("not-an-address"));
        putRaw("admin", "/rest/contacts/contact/" + id, map("id", id, "name", "IT-val")).statusCode(400);
        assertEquals(List.of(email), strings("SELECT email FROM public.\"Contact\" WHERE id = ?1", id), "unchanged");
    }

    @Test
    void aContactAddressTheFormAcceptsIsStored() {
        // The contact form's email() validator accepts these; UserService.EMAIL_PATTERN would refuse each (a hyphen
        // in a later domain label, a '+' after a dot in the local part, an apostrophe)
        String u = unique();
        for (String email : List.of("it-val-" + u + "@mail.my-company.dk", "it.val+" + u + "@example.dk",
                "it-val'" + u + "@example.dk")) {
            int id = ((Number) post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-val")).get("id")).intValue();
            cleanups.add(() -> delete("admin", "/rest/contacts/contact/" + id));
            assertEquals(List.of(email), strings("SELECT email FROM public.\"Contact\" WHERE id = ?1", id));

            // and an update that keeps it is accepted
            put("admin", "/rest/contacts/contact/" + id, map("id", id, "email", email, "name", "IT-val renamed"));
            assertEquals(List.of("IT-val renamed"), strings("SELECT name FROM public.\"Contact\" WHERE id = ?1", id));
        }
    }

    @Test
    void aStoredContactWhoseAddressTheFormRefusesCanStillBeEdited() {
        String email = "it-val-" + unique() + "@example.org";
        int id = ((Number) post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-val")).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/contacts/contact/" + id));
        // An address stored before the check, which the form would refuse (a space)
        String legacy = "it-val legacy " + unique() + "@example.org";
        execute("UPDATE public.\"Contact\" SET email = ?1 WHERE id = ?2", legacy, id);

        // Its name can be edited while the address stays as it is
        put("admin", "/rest/contacts/contact/" + id, map("id", id, "email", legacy, "name", "IT-val renamed"));
        assertEquals(List.of("IT-val renamed"), strings("SELECT name FROM public.\"Contact\" WHERE id = ?1", id));

        // A change of address is checked: to another invalid one is refused, to a valid one is stored
        putRaw("admin", "/rest/contacts/contact/" + id, map("id", id, "email", legacy + "x", "name", "IT-val"))
                .statusCode(400).body("detail", containsString("not an e-mail address"));
        putRaw("admin", "/rest/contacts/contact/" + id, map("id", id, "email", "", "name", "IT-val"))
                .statusCode(400).body("detail", containsString("needs an e-mail address"));
        assertEquals(List.of(legacy), strings("SELECT email FROM public.\"Contact\" WHERE id = ?1", id), "unchanged");
        put("admin", "/rest/contacts/contact/" + id, map("id", id, "email", email, "name", "IT-val"));
        assertEquals(List.of(email), strings("SELECT email FROM public.\"Contact\" WHERE id = ?1", id));
    }
}
