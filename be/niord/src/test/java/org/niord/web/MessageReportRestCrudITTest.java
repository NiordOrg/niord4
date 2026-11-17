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

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.util.TimeUtils;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the report create/update/delete <em>lifecycle</em> of
 * {@link MessageReportRestService}, complementing {@link MessageReportRestServiceITTest} (which covers the
 * static read endpoints and the per-endpoint authorization matrix). This class drives full CRUD round-trips
 * against {@code POST /report/}, {@code PUT /report/{id}} and {@code DELETE /report/{id}} and verifies the
 * mutations by reading back the persisted state through the {@code SYSADMIN}-only {@code GET /all} listing.
 *
 * <p><b>Authorization model under test.</b> The report-CRUD handlers
 * ({@link MessageReportRestService#createReport}, {@code updateReport}, {@code deleteReport}) now each carry
 * {@code @RolesAllowed(SYSADMIN)}, matching their sibling {@code POST /upload-reports}. Previously they only
 * inherited the class-level {@code @PermitAll} and were reachable <em>anonymously</em> — an unauthenticated
 * caller could create, mutate and delete report definitions that feed PDF rendering. These tests pin the
 * fix: the happy paths require a sysadmin token, an anonymous caller is rejected with {@code 401} (the
 * container's JSR-250 entry point) and an authenticated non-sysadmin (e.g. admin, one tier too low) with
 * {@code 403}, and in both rejection cases <em>no persistence side effect</em> occurs.
 *
 * <p><b>Update-to-missing-report behaviour.</b> {@code PUT /report/{id}}
 * for an id that does not exist reaches {@link org.niord.core.report.FmReportService#updateReport}, which
 * throws a plain {@code IllegalArgumentException("Cannot update non-existing report ...")}. There is no
 * {@code ExceptionMapper} for {@code IllegalArgumentException}, so the unhandled runtime exception is mapped
 * to {@code HTTP 500} rather than a client error such as {@code 404}. After the WEB1 fix this path is only
 * reachable by an authenticated sysadmin (anonymous is rejected first), so the pinning test now drives it
 * with a sysadmin token; it asserts the actual {@code 500} and is named {@code ..._documentsBug}. See
 * docs/potential-errors.md (REPORT1).
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}); see {@link KeycloakTestTokens} for the per-tier token matrix.
 * Authenticated requests carry {@code NiordDomain: niord-nw} so the augmentor realizes the full
 * role chain and the programmatic {@code SYSADMIN} check on {@code GET /all} passes.
 *
 * <p>HTTP requests run in the server's own transaction, which is not rolled back, so created reports are
 * tracked by id and best-effort deleted (as sysadmin) in {@link #cleanup()} using unique
 * {@code IT-CRUD-REPORT-} ids that never collide with seeded rows or with the sibling class'
 * {@code IT-REST-REPORT-} ids.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MessageReportRestCrudITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String ALL    = "/rest/message-reports/all";
    private static final String REPORT = "/rest/message-reports/report/";
    private static final String EXPAND = "/rest/message-reports/expand-params";

    @Autowired
    private DomainLookupService domainLookupService;

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Create/update/delete now require SYSADMIN (bug WEB1 fixed), so cleanup deletes with a sysadmin
        // token, best-effort.
        for (String id : createdIds) {
            try {
                as(accessToken()).when().delete(REPORT + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIds.clear();
    }

    /** Body for a minimal, valid report with the given reportId and name. */
    private static Map<String, Object> body(String reportId, String name) {
        return Map.of(
                "reportId", reportId,
                "name", name,
                "templatePath", "/x.ftl");
    }

    /** A unique id that never collides with seeded rows or the sibling class' ids. */
    private static String uniqueId() {
        return "IT-CRUD-REPORT-" + System.nanoTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /report/{id} — update round-trip as sysadmin. Create, rename via PUT, then confirm the new
    // name is what the SYSADMIN-only /all listing reports for that id (the update actually persisted).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateReport_asSysadminRenamePersists_andIsVisibleInAllListing() {
        String id = uniqueId();
        createdIds.add(id);

        // Create the report as sysadmin.
        as(accessToken()).contentType(ContentType.JSON).body(body(id, "Original Name"))
                .when().post(REPORT)
                .then().statusCode(200)
                .body("reportId", is(id))
                .body("name", is("Original Name"));

        // Update the name via PUT. The PUT response echoes the new name.
        as(accessToken()).contentType(ContentType.JSON).body(body(id, "Updated Name"))
                .when().put(REPORT + id)
                .then().statusCode(200)
                .body("reportId", is(id))
                .body("name", is("Updated Name"));

        // Verify the rename actually persisted: the SYSADMIN-only /all listing shows the new name for
        // exactly this id (and never the old one).
        as(accessToken()).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("reportId", hasItem(id))
                .body("find { it.reportId == '" + id + "' }.name", is("Updated Name"));
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /report/{id} — delete round-trip as sysadmin. Create, delete, then confirm the id is no
    // longer present in the SYSADMIN-only /all listing (the row was actually removed).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deleteReport_asSysadminRemovesRow_andIsGoneFromAllListing() {
        String id = uniqueId();
        // Tracked so a failure mid-test still cleans up; the test removes it from tracking after deleting.
        createdIds.add(id);

        // Create the report as sysadmin.
        as(accessToken()).contentType(ContentType.JSON).body(body(id, "To Be Deleted"))
                .when().post(REPORT)
                .then().statusCode(200)
                .body("reportId", is(id));

        // Confirm it is present before deletion.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", hasItem(id));

        // Delete the report as sysadmin.
        as(accessToken()).when().delete(REPORT + id)
                .then().statusCode(204);

        // Verify the row is actually gone: the SYSADMIN-only /all listing no longer contains the id.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("reportId", not(hasItem(id)));

        // Already deleted; nothing left for cleanup() to remove.
        createdIds.remove(id);
    }

    // ------------------------------------------------------------------------------------------- //
    // WEB1 fix: create/update/delete are now @RolesAllowed(SYSADMIN). An anonymous caller is rejected
    // with 401 and a non-sysadmin (admin, one tier too low) with 403 — and neither leaves a side effect.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createReport_rejectsAnonymousWith401_andDoesNotPersist() {
        String id = uniqueId();

        // Anonymous create is rejected by JSR-250 method security before the handler runs.
        given().contentType(ContentType.JSON).body(body(id, "Anon Create"))
                .when().post(REPORT)
                .then().statusCode(401);

        // No row was created.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", not(hasItem(id)));
    }

    @Test
    void createReport_forbidsAdminWith403_andDoesNotPersist() {
        String id = uniqueId();

        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin' -> 403.
        as(accessToken("admin")).contentType(ContentType.JSON).body(body(id, "Admin Create"))
                .when().post(REPORT)
                .then().statusCode(403);

        // No row was created.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", not(hasItem(id)));
    }

    @Test
    void updateReport_rejectsAnonymousWith401_andDoesNotMutate() {
        String id = uniqueId();
        createdIds.add(id);

        // Seed a report as sysadmin.
        as(accessToken()).contentType(ContentType.JSON).body(body(id, "Original Name"))
                .when().post(REPORT)
                .then().statusCode(200);

        // An anonymous rename attempt is rejected.
        given().contentType(ContentType.JSON).body(body(id, "Hacked Name"))
                .when().put(REPORT + id)
                .then().statusCode(401);

        // The persisted name is unchanged.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("find { it.reportId == '" + id + "' }.name", is("Original Name"));
    }

    @Test
    void deleteReport_rejectsAnonymousWith401_andDoesNotRemove() {
        String id = uniqueId();
        createdIds.add(id);

        // Seed a report as sysadmin.
        as(accessToken()).contentType(ContentType.JSON).body(body(id, "Keep Me"))
                .when().post(REPORT)
                .then().statusCode(200);

        // An anonymous delete attempt is rejected.
        given().when().delete(REPORT + id)
                .then().statusCode(401);

        // The row is still present.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", hasItem(id));
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /report/{id} for a non-existent id: MessageReportRestService.updateReport null-checks the
    // report and throws NiordWebException(.., 404), so a missing report is a client 404 rather than an
    // unmapped HTTP 500. The path is only reachable by a sysadmin (anonymous is rejected with 401
    // first).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateReport_nonExistentIdAsSysadmin_returns404() {
        // An id that is guaranteed not to exist (and is never created by this test).
        String missingId = uniqueId();

        // Path id and body reportId match (so the 400 mismatch guard is NOT triggered); the handler
        // null-checks the report and throws NiordWebException(.., 404) for the missing row (REPORT1).
        as(accessToken()).contentType(ContentType.JSON).body(body(missingId, "Does Not Matter"))
                .when().put(REPORT + missingId)
                .then().statusCode(404);

        // Sanity: the failed update must not have created a row as a side effect.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", not(hasItem(missingId)));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /expand-params: the Print reports page's Test prints the report as the editor holds
    // it, saved or not. Its ${year} and ${week} are expanded as GET /reports expands a stored report's,
    // in the current domain's zone, so the test PDF's header does not print them raw. Nothing is stored.
    // ------------------------------------------------------------------------------------------- //

    /** The expanded values a report naming a week gets today in niord-nw's zone: year, year-2, week, week-2 */
    private List<String> expectedWeekValuesNow() {
        Domain nw = domainLookupService.findByDomainId(NW_DOMAIN);
        ZoneId zone = TimeUtils.zoneOrDefault(nw == null ? null : nw.getTimeZone());
        Date now = new Date();
        int year = TimeUtils.isoWeekBasedYear(now, zone);
        int week = TimeUtils.isoWeek(now, zone);
        return List.of(String.valueOf(year), String.valueOf(year).substring(2), String.valueOf(week),
                String.format("%02d", week));
    }

    @Test
    void expandParams_asSysadmin_expandsTheParametersOfAnUnsavedReport_andStoresNothing() {
        String id = uniqueId();
        Map<String, Object> params = new HashMap<>();
        params.put("year", "${year}");
        params.put("week", "${week}");
        params.put("edition", "${year-2-digits}/${week-2-digits}");
        params.put("copies", 3);
        params.put("note", "Weekly edition");
        Map<String, Object> report = new HashMap<>(body(id, "Unsaved Report"));
        report.put("params", params);
        report.put("properties", Map.of("title", "${year}"));

        // Read before and after the call, so that a call across midnight still has one of them to match
        List<String> before = expectedWeekValuesNow();
        io.restassured.path.json.JsonPath answer = as(accessToken()).contentType(ContentType.JSON).body(report)
                .when().post(EXPAND)
                .then().statusCode(200).contentType(ContentType.JSON)
                .extract().jsonPath();
        List<String> after = expectedWeekValuesNow();

        List<String> got = List.of(answer.getString("params.year"), answer.getString("params.edition"),
                answer.getString("params.week"));
        List<String> fromBefore = List.of(before.get(0), before.get(1) + "/" + before.get(3), before.get(2));
        List<String> fromAfter = List.of(after.get(0), after.get(1) + "/" + after.get(3), after.get(2));
        assertTrue(got.equals(fromBefore) || got.equals(fromAfter),
                "year, edition and week are expanded in niord-nw's zone (the week-based year, as a report naming "
                        + "a week gets): got " + got + ", expected " + fromBefore + " or " + fromAfter);

        // The rest of the report comes back as sent: the id and the name the test dialog shows, a value that is
        // not text, a text without a token, and the properties, which are not expanded
        assertEquals(id, answer.getString("reportId"));
        assertEquals("Unsaved Report", answer.getString("name"));
        assertEquals("/x.ftl", answer.getString("templatePath"));
        assertEquals(3, answer.getInt("params.copies"));
        assertEquals("Weekly edition", answer.getString("params.note"));
        assertEquals("${year}", answer.getString("properties.title"));

        // The report was not stored
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", not(hasItem(id)));
    }

    @Test
    void expandParams_ofAReportWithoutParameters_answersItUnchanged() {
        String id = uniqueId();
        Map<String, Object> report = new HashMap<>(body(id, "No Parameters"));
        report.put("params", Map.of());

        as(accessToken()).contentType(ContentType.JSON).body(report)
                .when().post(EXPAND)
                .then().statusCode(200)
                .body("reportId", is(id))
                .body("name", is("No Parameters"))
                .body("params.size()", is(0));
    }

    @Test
    void expandParams_rejectsAnonymousWith401_andAdminWith403() {
        Map<String, Object> report = new HashMap<>(body(uniqueId(), "Refused"));
        report.put("params", Map.of("year", "${year}"));

        given().contentType(ContentType.JSON).body(report)
                .when().post(EXPAND)
                .then().statusCode(401);
        as(accessToken("admin")).contentType(ContentType.JSON).body(report)
                .when().post(EXPAND)
                .then().statusCode(403);
    }
}
