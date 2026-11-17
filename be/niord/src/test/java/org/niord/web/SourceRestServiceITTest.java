/*
 * Copyright 2016 Danish Maritime Authority.
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for {@link SourceRestService}: the HTTP contract of the {@code /rest/sources}
 * endpoints, including authorization. The GET reads ({@code /search}, {@code /search/{ids}},
 * {@code /all}, {@code /source/{id}}) are {@code @PermitAll}; the {@code POST}/{@code PUT}/{@code DELETE}
 * source endpoints and {@code upload-sources} require the {@code admin} role, exercised with a real
 * Keycloak token (see {@link KeycloakTestTokens}).
 *
 * <p>Reads and the negative-security cases need no token. Positive role-based cases create data that is
 * committed (HTTP requests run in the server's own transaction, which {@code @Transactional} cannot
 * roll back), so created sources are tracked and deleted in {@link #cleanup()} using unique
 * {@code IT-REST-} names that never occur in the seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SourceRestServiceITTest extends ITTest {

    // Stable seed BUSINESS KEYS (see db/niord-test-seed.sql, SourceDesc data): the seeded sources
    // are identified by their unique English name, not by a hardcoded numeric id. PostgreSQL's
    // per-entity sequences assign different ids than the old MySQL shared sequence, so the concrete
    // ids must be RESOLVED at runtime (see resolveSeedSourceIdByEnName) instead of being pinned.
    private static final String SEED_SOURCE_DMI_EN_NAME = "The Danish Meteorological Institute";
    private static final String SEED_SOURCE_DMA_EN_NAME = "The Danish Maritime Authority";

    // Any syntactically-valid id for the negative-security cases: the request is rejected with 401
    // BEFORE the handler ever looks the source up, so the concrete id value is immaterial.
    private static final int ANY_SOURCE_ID = 1;

    private final List<Integer> createdSourceIds = new ArrayList<>();

    /**
     * Resolves the id of a seeded source from its unique English name via the public search
     * endpoint. This keeps the seed-id lookups stable across the MySQL -> PostgreSQL migration,
     * where the generated ids changed but the business data did not.
     */
    private int resolveSeedSourceIdByEnName(String enName) {
        return given()
                .queryParam("lang", "en")
                .queryParam("name", enName)
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .extract().path("find { it.descs.find { d -> d.lang == 'en' }.name == '" + enName + "' }.id");
    }

    @AfterEach
    void cleanup() {
        for (Integer id : createdSourceIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/sources/source/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdSourceIds.clear();
    }

    /** Creates an active source with a single English description and tracks it for cleanup. */
    private int createSourceAsAdmin(String name, String abbreviation) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "active", true,
                        "descs", List.of(Map.of(
                                "lang", "en",
                                "name", name,
                                "abbreviation", abbreviation))))
                .when().post("/rest/sources/source/")
                .then().statusCode(200)
                .extract().path("id");
        createdSourceIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllReturnsSeededSourcesWithoutToken() {
        given()
                .queryParam("lang", "en")
                .when().get("/rest/sources/all")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].id", notNullValue());
    }

    @Test
    void getAllRespectsLimit() {
        given()
                .queryParam("lang", "en")
                .queryParam("limit", 3)
                .when().get("/rest/sources/all")
                .then().statusCode(200)
                .body("size()", is(3));
    }

    @Test
    void getSourceByIdReturnsSeededSource() {
        int dmiId = resolveSeedSourceIdByEnName(SEED_SOURCE_DMI_EN_NAME);

        given()
                .when().get("/rest/sources/source/" + dmiId)
                .then().statusCode(200)
                .body("id", is(dmiId))
                .body("descs.find { it.lang == 'en' }.name", is(SEED_SOURCE_DMI_EN_NAME));
    }

    @Test
    void searchByNameMatchesSeededSource() {
        int dmiId = resolveSeedSourceIdByEnName(SEED_SOURCE_DMI_EN_NAME);

        given()
                .queryParam("lang", "en")
                .queryParam("name", "Meteorological")
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("id", hasItem(dmiId));
    }

    @Test
    void searchByNameWithNoMatchReturnsEmptyList() {
        given()
                .queryParam("lang", "en")
                .queryParam("name", "IT-REST-NoSuchSourceXYZ")
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchOnlyReturnsActiveSourcesByDefault() {
        // Create the source as INACTIVE directly via POST, which keeps this search test independent of
        // the update path. (Deactivating via PUT also works now that SRC1 is fixed - see
        // updateSourceToDeactivateWithSameLanguageSucceeds.)
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "active", false,
                        "descs", List.of(Map.of(
                                "lang", "en",
                                "name", "IT-REST-InactiveSearch",
                                "abbreviation", "ITRIS"))))
                .when().post("/rest/sources/source/")
                .then().statusCode(200)
                .body("active", is(false))
                .extract().path("id");
        createdSourceIds.add(id);

        // The default (inactive=false) search must not return the inactive source.
        given()
                .queryParam("lang", "en")
                .queryParam("name", "IT-REST-InactiveSearch")
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .body("findAll { it.id == " + id + " }.size()", is(0));

        // ...but including inactive sources surfaces it again.
        given()
                .queryParam("lang", "en")
                .queryParam("name", "IT-REST-InactiveSearch")
                .queryParam("inactive", true)
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .body("id", hasItem(id));
    }

    @Test
    void searchByIdsReturnsRequestedSeededSources() {
        int dmiId = resolveSeedSourceIdByEnName(SEED_SOURCE_DMI_EN_NAME);
        int dmaId = resolveSeedSourceIdByEnName(SEED_SOURCE_DMA_EN_NAME);

        given()
                .when().get("/rest/sources/search/" + dmiId + "," + dmaId)
                .then().statusCode(200)
                .body("size()", is(2))
                .body("id", hasItem(dmiId))
                .body("id", hasItem(dmaId));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createSourceWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("descs", List.of(Map.of("lang", "en", "name", "x", "abbreviation", "x"))))
                .when().post("/rest/sources/source/")
                .then().statusCode(401);
    }

    @Test
    void updateSourceWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("id", ANY_SOURCE_ID))
                .when().put("/rest/sources/source/" + ANY_SOURCE_ID)
                .then().statusCode(401);
    }

    @Test
    void deleteSourceWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/sources/source/" + ANY_SOURCE_ID)
                .then().statusCode(401);
    }

    @Test
    void uploadSourcesWithoutTokenIsUnauthorized() {
        given().multiPart("data", "sources.json", "[]".getBytes())
                .when().post("/rest/sources/upload-sources")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — admin with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createSourceAsAdminPersistsAndIsReadable() {
        int id = createSourceAsAdmin("IT-REST-Create", "ITRC");

        given()
                .when().get("/rest/sources/source/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("active", is(true))
                .body("descs.find { it.lang == 'en' }.name", is("IT-REST-Create"))
                .body("descs.find { it.lang == 'en' }.abbreviation", is("ITRC"));
    }

    @Test
    void updateSourceAsAdminWithSameLanguageRenames() {
        // FIXED (see docs/potential-errors.md, SRC1): SourceService.updateSource no longer clear()s the
        // descs before copyDescsAndRemoveBlanks(). copyDescs() reuses the existing desc per language via
        // checkCreateDesc(), so a same-language rename updates the row in place instead of creating a
        // duplicate (lang, source_id) row that collided on flush. The update now succeeds (200).
        int id = createSourceAsAdmin("IT-REST-Upd", "ITRU");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "active", true,
                        "descs", List.of(Map.of(
                                "lang", "en",
                                "name", "IT-REST-Upd-Renamed",
                                "abbreviation", "ITRU2"))))
                .when().put("/rest/sources/source/" + id)
                .then().statusCode(200);

        // The update took effect: the renamed name/abbreviation are now persisted.
        given()
                .when().get("/rest/sources/source/" + id)
                .then().statusCode(200)
                .body("descs.find { it.lang == 'en' }.name", is("IT-REST-Upd-Renamed"))
                .body("descs.find { it.lang == 'en' }.abbreviation", is("ITRU2"));
    }

    @Test
    void updateSourceToDeactivateWithSameLanguageSucceeds() {
        // FIXED (see docs/potential-errors.md, SRC1): a deactivation that retains the existing "en"
        // desc no longer collides on flush, so the active flag can now be toggled via the REST update.
        int id = createSourceAsAdmin("IT-REST-Deactivate", "ITRD");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "active", false,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Deactivate", "abbreviation", "ITRD"))))
                .when().put("/rest/sources/source/" + id)
                .then().statusCode(200);

        // The deactivation took effect: the source is no longer active.
        given()
                .when().get("/rest/sources/source/" + id)
                .then().statusCode(200)
                .body("active", is(false));
    }

    @Test
    void updateSourceWithMismatchedIdReturns400() {
        int id = createSourceAsAdmin("IT-REST-Mismatch", "ITRM");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id + 1,
                        "active", true,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Mismatch", "abbreviation", "ITRM"))))
                .when().put("/rest/sources/source/" + id)
                .then().statusCode(400);
    }

    @Test
    void deleteSourceAsAdminRemovesIt() {
        int id = createSourceAsAdmin("IT-REST-Del", "ITRDL");

        given().auth().oauth2(accessToken())
                .when().delete("/rest/sources/source/" + id)
                .then().statusCode(204);

        // No longer surfaced by the by-ids read.
        given()
                .when().get("/rest/sources/search/" + id)
                .then().statusCode(200)
                .body("size()", is(0));

        // Already gone — drop it from the cleanup list so @AfterEach does not log a 2nd delete.
        createdSourceIds.remove((Integer) id);
    }

    @Test
    void createSourceRoundTripsViaSearchByName() {
        int id = createSourceAsAdmin("IT-REST-Searchable", "ITRSE");

        given()
                .queryParam("lang", "en")
                .queryParam("name", "IT-REST-Searchable")
                .when().get("/rest/sources/search")
                .then().statusCode(200)
                .body("id", hasItem(id))
                .body("find { it.id == " + id + " }.descs.find { it.lang == 'en' }.abbreviation", is("ITRSE"));
    }
}
