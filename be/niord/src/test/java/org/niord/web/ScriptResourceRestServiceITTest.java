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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link ScriptResourceRestService}: the HTTP contract and authorization of the
 * {@code /rest/script-resources} endpoints, which manage Freemarker templates and JavaScript resources.
 *
 * <p><b>Authorization model under test.</b> The class default is {@code @RolesAllowed(SYSADMIN)}, so the
 * write/CRUD endpoints (create/update/delete script-resource), the multipart
 * {@code /upload-script-resources} and the {@code /history} reader are all SYSADMIN-only. The single
 * exception is {@code GET /all}, which is declared {@code @PermitAll} but enforces the {@code ADMIN} role
 * <em>programmatically</em> inside the handler via {@code userService.isCallerInRole(ADMIN)}, throwing
 * {@code NiordWebException(403)} otherwise. Because {@code @PermitAll} lets even an anonymous caller
 * reach the handler, {@code /all} rejects the wrong identity with {@code 403} (the programmatic check)
 * rather than the container's {@code 401}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). The four seeded identities (see {@link KeycloakTestTokens})
 * give one token per tier. Every authenticated request carries {@code NiordDomain: niord-nw} so the
 * domain augmentor realizes the full role chain. A missing token on a {@code @RolesAllowed} endpoint yields
 * the container's {@code 401}; an authenticated identity one tier too low yields {@code 403}. On the
 * programmatic {@code /all} endpoint, an anonymous caller is rejected with {@code 403} (not {@code 401})
 * because the {@code @PermitAll} handler is reached before the role check throws.
 *
 * <p><b>Seeded-data oracle.</b> The fixture's {@code ScriptResource} table holds the base product's 80 templates
 * and scripts and the sample's 4, so {@code /all} returns a JSON list whose paths include the base product's
 * {@code templates/messages/message-list-pdf.ftl}, a row no test deletes (a bound on the size would break on the rows
 * other tests add or delete). The {@code /history} of an unknown resource id is an empty list ({@code findById}
 * returns null and the handler returns {@code Collections.emptyList()}).
 *
 * <p><b>No read writes, nothing reloads.</b> A {@code ?path=} on {@code /all} writes no row, and the former
 * {@code /reload} is gone, so nothing copies a class-path file over a stored row; since C4.2a nothing reads a
 * template from the class path at all. An upload adds the missing paths of its file, overwrites an existing path only
 * when the sysadmin ticks it, and records the importing user and the source {@code import} in the history
 * ({@link ScriptResourceImportITTest} covers the preview, the ticks and the refusals).
 *
 * <p><b>Create/update/delete oracle.</b> {@code createScriptResource} echoes the persisted row with a
 * generated {@code id} and the submitted {@code path}. {@code updateScriptResource} rejects a request whose
 * path {@code {id}} disagrees with the body {@code id} via {@code NiordWebException(400)}.
 * {@code deleteScriptResource} is a {@code void} handler, so a successful delete returns {@code 204}.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll back, so
 * created resources are tracked by id and best-effort deleted as sysadmin in {@link #cleanup()} using
 * unique {@code templates/it/it-<nanoTime>.js} paths that never collide with seeded data.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class ScriptResourceRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String ALL             = "/rest/script-resources/all";
    private static final String SCRIPT_RESOURCE  = "/rest/script-resources/script-resource/";
    private static final String RELOAD           = "/rest/script-resources/reload/";
    private static final String UPLOAD           = "/rest/script-resources/upload-script-resources";

    /** A base product template the fixture holds as a row (niord/bootstrap/templates/), which no test deletes */
    private static final String BASE_TEMPLATE = "templates/messages/message-list-pdf.ftl";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<Integer> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdIds) {
            try {
                as(accessToken()).when().delete(SCRIPT_RESOURCE + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIds.clear();
    }

    /** A path guaranteed unique per call, so created rows never collide. */
    private static String uniquePath() {
        return "templates/it/it-" + System.nanoTime() + ".js";
    }

    /** Body for a minimal, valid JS script resource at the given path. */
    private static Map<String, Object> body(String path, String content) {
        return Map.of("type", "JS", "path", path, "content", content);
    }

    /** Creates a JS script resource as sysadmin, asserts 200 + echoed path, tracks + returns its id. */
    private Integer createAsSysadmin(String path) {
        Integer id = as(accessToken()).contentType(ContentType.JSON).body(body(path, "var x=1;"))
                .when().post(SCRIPT_RESOURCE)
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("path", is(path))
                .extract().path("id");
        createdIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /all — @PermitAll method with a programmatic ADMIN check. Anonymous/editor reach the
    // handler then get 403; admin & sysadmin get the JSON list of every row.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAll_rejectsAnonymousWith403() {
        // @PermitAll lets the anonymous caller into the handler; the programmatic ADMIN check then 403s.
        given().when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAll_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        as(accessToken("editor")).when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAll_allowsAdminAndReturnsJsonList() {
        as(accessToken("admin")).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("path", hasItem(BASE_TEMPLATE));
    }

    @Test
    void getAll_allowsSysadmin() {
        as(accessToken()).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("path", hasItem(BASE_TEMPLATE));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /script-resource/ — class default @RolesAllowed(SYSADMIN). anon -> 401; admin one tier
    // too low -> 403; sysadmin creates the row and the body echoes the generated id and the path.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void create_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(body(uniquePath(), "var x=1;"))
                .when().post(SCRIPT_RESOURCE).then().statusCode(401);
    }

    @Test
    void create_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'.
        as(accessToken("admin")).contentType(ContentType.JSON).body(body("templates/it/x.js", "var x=1;"))
                .when().post(SCRIPT_RESOURCE).then().statusCode(403);
    }

    @Test
    void create_allowsSysadminAndEchoesIdAndPath() {
        String path = uniquePath();
        createAsSysadmin(path);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /script-resource/{id} — @RolesAllowed(SYSADMIN). Consistent id -> 200; mismatched path/body
    // id -> 400 (NiordWebException(400) before any persistence).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void update_consistentIdReturns200() {
        String path = uniquePath();
        Integer id = createAsSysadmin(path);

        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("id", id, "type", "JS", "path", path, "content", "var y=2;"))
                .when().put(SCRIPT_RESOURCE + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("path", is(path));
    }

    @Test
    void update_withMismatchedIdReturns400() {
        String path = uniquePath();
        Integer id = createAsSysadmin(path);

        // Path id and body id disagree -> NiordWebException(400).
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("id", id + 1, "type", "JS", "path", path, "content", "var y=2;"))
                .when().put(SCRIPT_RESOURCE + id)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /script-resource/{id} — @RolesAllowed(SYSADMIN). anon -> 401; sysadmin -> 204 (void).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void delete_rejectsAnonymousWith401() {
        given().when().delete(SCRIPT_RESOURCE + "999999").then().statusCode(401);
    }

    @Test
    void delete_asSysadminReturns204() {
        String path = uniquePath();
        Integer id = createAsSysadmin(path);

        as(accessToken()).when().delete(SCRIPT_RESOURCE + id)
                .then().statusCode(is(204));
        createdIds.remove(id);
    }

    // ------------------------------------------------------------------------------------------- //
    // No read writes a row: a ?path= on GET /all is ignored, so a class-path file with no row stays
    // without one. The class-path file is used by this test only.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAll_withPathQueryWritesNoRow() {
        String classPathOnly = "test-scripts/script-resources-get-it.js";

        List<String> paths = as(accessToken("admin")).queryParam("path", classPathOnly).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .extract().jsonPath().getList("path", String.class);
        assertFalse(paths.contains(classPathOnly), "the GET must not list a path it was only asked about");

        List<String> after = as(accessToken("admin")).when().get(ALL)
                .then().statusCode(200)
                .extract().jsonPath().getList("path", String.class);
        assertFalse(after.contains(classPathOnly), "the GET must not have copied the class-path file into a row");
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /reload/ is gone: nothing copies the class path over a stored row.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reload_endpointIsGone() {
        as(accessToken()).when().post(RELOAD).then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /upload-script-resources — @RolesAllowed(SYSADMIN), multipart. anon (with a part) -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void upload_rejectsAnonymousWith401() {
        given().multiPart("file", "x.json", new byte[]{1})
                .when().post(UPLOAD).then().statusCode(401);
    }

    /**
     * The import runs as a batch job with no security context, so the history names the user who uploaded the
     * file: the same user a sysadmin's own create records.
     */
    @Test
    void upload_recordsTheImportingUserInHistory() throws InterruptedException {
        // The user a sysadmin request records, whatever the realm calls it
        Integer ownId = createAsSysadmin(uniquePath());
        String sysadmin = as(accessToken()).when().get(SCRIPT_RESOURCE + ownId + "/history")
                .then().statusCode(200)
                .extract().path("[0].user");
        assertNotNull(sysadmin, "precondition: a sysadmin's own create records the user");

        String path = uniquePath();
        String json = "[{\"type\":\"JS\",\"path\":\"" + path + "\",\"content\":\"var x=1;\"}]";
        as(accessToken())
                .multiPart("file", "script-resources.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post(UPLOAD)
                .then().statusCode(200);

        Integer importedId = awaitResourceId(path);
        assertNotNull(importedId, "the uploaded resource must be imported by the script-resource-import job");
        createdIds.add(importedId);

        io.restassured.path.json.JsonPath history = as(accessToken())
                .when().get(SCRIPT_RESOURCE + importedId + "/history")
                .then().statusCode(200)
                .extract().jsonPath();
        List<String> users = history.getList("user", String.class);
        assertEquals(1, users.size());
        assertEquals(sysadmin, users.get(0));
        assertEquals(List.of("import"), history.getList("source", String.class), "the import records its source");
    }

    /** Polls GET /all until a resource with the path appears, or ~60 s elapse; returns its id or null */
    private Integer awaitResourceId(String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> all = as(accessToken()).when().get(ALL)
                    .then().statusCode(200)
                    .extract().jsonPath().getList("$");
            for (Map<String, Object> r : all) {
                if (path.equals(r.get("path"))) {
                    return (Integer) r.get("id");
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /script-resource/{id}/history — @RolesAllowed(SYSADMIN). anon -> 401; sysadmin on an
    // unknown id -> 200 with an empty list (findById null -> Collections.emptyList()).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void history_rejectsAnonymousWith401() {
        given().when().get(SCRIPT_RESOURCE + "999999/history").then().statusCode(401);
    }

    @Test
    void history_unknownResourceReturnsEmptyList() {
        as(accessToken()).when().get(SCRIPT_RESOURCE + "999999/history")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(0));
    }
}
