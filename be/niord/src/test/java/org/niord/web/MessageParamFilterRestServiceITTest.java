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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for {@link MessageParamFilterRestService}: the HTTP contract, authorization and
 * per-user data ownership of the {@code /rest/filters} endpoints.
 *
 * <p><b>Authorization model under test.</b> The whole class is declared {@code @PermitAll}, so the
 * container never rejects a caller on its own. Instead every handler calls {@code checkUserLoggedIn()},
 * which resolves {@code userService.currentUser()} and throws {@code NiordWebException(403)} when
 * there is no current user. Therefore an <em>anonymous</em> caller reaches the handler and is rejected
 * with {@code 403} (not the container's {@code 401}); any authenticated identity carrying the
 * {@code NiordDomain: niord-nw} header (which the augmentor turns into the {@code user} role and a
 * resolvable current user) passes the login check. This is why all positive cases use {@code as(accessToken("user"))}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}; the seeded
 * identities (see {@link KeycloakTestTokens}) give one token per tier. None of these endpoints checks a
 * specific role beyond "logged in", so the lowest tier ({@code user}) is sufficient for every operation.
 *
 * <p><b>Ownership oracle.</b> A {@code MessageParamFilter} is owned by the user that created it.
 * {@code MessageParamFilterService.findById} returns {@code null} for an unknown id (so a missing filter
 * surfaces as a {@code 204} from the {@code void}/null-VO handlers), but when the row exists and is owned
 * by a <em>different</em> user it throws a {@code NiordWebException(403)} (WEB2, rendered as an
 * RFC 9457 {@code ProblemDetail} by Spring), so a cross-user read produces a clean HTTP {@code 403} rather
 * than an opaque {@code 500}; the test additionally asserts the {@code 403} body does not leak the
 * filter name.
 *
 * <p><b>No seeded filters.</b> The seed contains no message-parameter filters, so {@code /all} starts
 * empty and every assertion creates its own uniquely named ({@code IT-FILTER-<nanoTime>}) filter.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll back,
 * so created filters are tracked by id and best-effort deleted as the owning user in {@link #cleanup()}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageParamFilterRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String ALL    = "/rest/filters/all";
    private static final String FILTER = "/rest/filters/filter";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (resolvable user). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<Integer> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdIds) {
            try {
                as(accessToken("user")).when().delete(FILTER + "/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIds.clear();
    }

    /** Body for a minimal, valid filter with the given (unique) name. */
    private static Map<String, Object> body(String name) {
        return Map.of("name", name, "parameters", "aton.k('x')");
    }

    /** Creates a filter as the user, asserts 200 + echoed name + assigned id, and tracks it for cleanup. */
    private int createAsUser(String name) {
        Integer id = as(accessToken("user")).contentType(ContentType.JSON).body(body(name))
                .when().post(FILTER)
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("name", is(name))
                .extract().path("id");
        createdIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /all — @PermitAll + programmatic login check. Anonymous reaches the handler then is
    // rejected with 403 (not the container's 401); an authenticated user passes.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAll_rejectsAnonymousWith403() {
        given().when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAll_allowsUserAndReturnsArray() {
        as(accessToken("user")).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /filter — @PermitAll + login check. Anonymous -> 403; authenticated user creates the
    // filter, which is echoed back with an assigned id and the submitted name.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void create_rejectsAnonymousWith403() {
        given().contentType(ContentType.JSON).body(body("IT-FILTER-" + System.nanoTime()))
                .when().post(FILTER).then().statusCode(403);
    }

    @Test
    void create_allowsUserAndEchoesNameAndId() {
        String name = "IT-FILTER-" + System.nanoTime();
        createAsUser(name);
    }

    /**
     * The administration app saves a filter with a trailing slash ({@code named-filters-api.service.ts}), as
     * most of its creates post; the backend maps both spellings, as its other creates do. Before, only
     * {@code /filter} was mapped and the app's save answered 404 (FILTER2).
     */
    @Test
    void aFilterPostedWithATrailingSlashIsStored() {
        String name = "IT-FILTER-" + System.nanoTime();
        Integer id = as(accessToken("user")).contentType(ContentType.JSON).body(body(name))
                .when().post(FILTER + "/")
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("name", is(name))
                .body("parameters", is("aton.k('x')"))
                .extract().path("id");
        createdIds.add(id);

        as(accessToken("user")).when().get(ALL)
                .then().statusCode(200)
                .body("name", hasItem(name));
    }

    @Test
    void anAnonymousPostWithATrailingSlashIsRefusedWith403() {
        given().contentType(ContentType.JSON).body(body("IT-FILTER-" + System.nanoTime()))
                .when().post(FILTER + "/").then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // Lifecycle: create -> get-by-id -> appears in /all -> delete -> get-by-id is empty (204).
    // This is the central persistence side-effect test.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createThenGetById_returnsTheCreatedFilter() {
        String name = "IT-FILTER-" + System.nanoTime();
        int id = createAsUser(name);

        as(accessToken("user")).when().get(FILTER + "/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("name", is(name));
    }

    @Test
    void createThenGetAll_listsTheCreatedFilterByName() {
        String name = "IT-FILTER-" + System.nanoTime();
        createAsUser(name);

        as(accessToken("user")).when().get(ALL)
                .then().statusCode(200)
                .body("name", hasItem(name));
    }

    @Test
    void delete_removesFilterThenGetByIdReturns204() {
        String name = "IT-FILTER-" + System.nanoTime();
        int id = createAsUser(name);

        // void handler + successful delete -> 204.
        as(accessToken("user")).when().delete(FILTER + "/" + id)
                .then().statusCode(204);
        createdIds.remove(Integer.valueOf(id));

        // findById now returns null -> null VO -> 204 (no body), confirming the row is gone.
        as(accessToken("user")).when().get(FILTER + "/" + id)
                .then().statusCode(204);
    }

    // ------------------------------------------------------------------------------------------- //
    // DATA-ISOLATION SECURITY: a filter owned by 'user' cannot be read by a different identity.
    // The ownership check in findById throws NiordWebException(403), rendered as an RFC 9457
    // ProblemDetail by Spring, so an ownership violation returns a clean 403 (WEB2 fixed) rather
    // than an opaque 500. We additionally assert the 403 body does not leak the owner's filter name.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getById_byDifferentUser_returns403AndDoesNotLeakName() {
        String name = "IT-FILTER-" + System.nanoTime();
        int id = createAsUser(name);

        // 'editor' is a different identity than the owning 'user'; the ownership check throws
        // NiordWebException(403) -> mapped to a clean HTTP 403 (not an opaque 500).
        as(accessToken("editor")).when().get(FILTER + "/" + id)
                .then().statusCode(403)
                // No data leak: the foreign user must not see the owner's filter name in the error body.
                .body(not(containsString(name)));
    }

    // ------------------------------------------------------------------------------------------- //
    // Edge case: getting an unknown id as the (authenticated) user is a clean miss -> findById
    // returns null -> null VO -> 204 (distinct from the cross-user ownership 500 above).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getById_unknownIdAsUserReturns204() {
        as(accessToken("user")).when().get(FILTER + "/2147483647")
                .then().statusCode(204);
    }
}
