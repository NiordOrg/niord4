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
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link TemplateExecutionRestService}: the HTTP contract and authorization of the
 * {@code /rest/templates} endpoints.
 *
 * <p><b>Authorization model under test.</b> The class is annotated {@code @PermitAll}, so unless an
 * endpoint declares its own {@code @RolesAllowed}, anyone (including anonymous callers) may reach it.
 * The parameter-type read endpoints ({@code /parameter-types}, {@code /parameter-types/export}) and the
 * two field-validation endpoints ({@code /validate-light-character}, {@code /validate-call-sign})
 * therefore answer anonymous callers with {@code 200}. The parameter-type write endpoints
 * ({@code POST /parameter-type/}, {@code PUT}/{@code DELETE /parameter-type/{id}} and the multipart
 * {@code /upload-param-types} import) carry {@code @RolesAllowed(SYSADMIN)}; template execution
 * ({@code PUT /execute}) carries {@code @RolesAllowed(USER)}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). Each seeded identity (see {@link KeycloakTestTokens}) gives one
 * token per tier. On a {@code @RolesAllowed} endpoint a missing token yields the container's {@code 401};
 * an authenticated identity one tier too low yields {@code 403}. So a SYSADMIN-only write rejects an
 * anonymous caller with {@code 401} and an authenticated {@code admin} (one tier too low) with {@code 403}.
 * Authenticated requests carry {@code NiordDomain: niord-nw} so the augmentor realizes the full
 * role chain (the only source of the {@code user} role).
 *
 * <p><b>Validation oracle.</b> {@code /validate-light-character} and {@code /validate-call-sign} return an
 * {@code NgRemoveValidateResponse} with the (possibly transformed) {@code value} and a boolean
 * {@code isValid}. {@code "Iso G 4s"} is a valid light character; garbage such as {@code "not a light zzz"}
 * parses as invalid. {@code "OUXR2"} is a valid call sign. {@code validate-call-sign} upper-cases the echoed
 * value before returning it.
 *
 * <p><b>Seeded data oracle.</b> The parameter-types seed (parameter-types.json) was imported, so
 * {@code /parameter-types} returns at least one row. {@code /parameter-types/export} drops the
 * system-managed standard types and resets ids, so it returns a (possibly empty) JSON list.
 *
 * <p>No successful SYSADMIN write is performed by this suite, so there is nothing to clean up.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TemplateExecutionRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String PARAM_TYPES        = "/rest/templates/parameter-types";
    private static final String PARAM_TYPES_EXPORT = "/rest/templates/parameter-types/export";
    private static final String PARAM_TYPE         = "/rest/templates/parameter-type/";
    private static final String UPLOAD_PARAM_TYPES = "/rest/templates/upload-param-types";
    private static final String EXECUTE            = "/rest/templates/execute";
    private static final String VALIDATE_LIGHT     = "/rest/templates/validate-light-character";
    private static final String VALIDATE_CALLSIGN  = "/rest/templates/validate-call-sign";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /parameter-types — @PermitAll. Anonymous reaches the handler and gets the seeded list.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getParamTypes_allowsAnonymousAndReturnsSeededTypes() {
        given().when().get(PARAM_TYPES)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(1));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /parameter-types/export — @PermitAll. Standard types removed, ids reset; still a JSON list.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportParamTypes_allowsAnonymousAndReturnsJsonList() {
        given().when().get(PARAM_TYPES_EXPORT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /parameter-type/ — @RolesAllowed(SYSADMIN). anon -> 401; admin one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createParamType_rejectsAnonymousWith401() {
        // The body must carry the polymorphic "type" discriminator (ParamTypeVo is
        // @JsonTypeInfo(use=NAME, property="type") with no defaultImpl). Authorization is enforced at
        // the method level (@EnableMethodSecurity), so @RequestBody deserialization runs first: a body
        // without "type" fails to bind and yields 400 before @RolesAllowed can reject. A well-formed
        // typed body binds, so the SYSADMIN check is what rejects the anonymous caller with 401.
        given().contentType(ContentType.JSON).body(Map.of("type", "LIST", "name", "IT"))
                .when().post(PARAM_TYPE)
                .then().statusCode(401);
    }

    @Test
    void createParamType_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'. The body carries "type" so it
        // binds and reaches the method-level @RolesAllowed(SYSADMIN) check (see the anonymous case above).
        as(accessToken("admin")).contentType(ContentType.JSON).body(Map.of("type", "LIST", "name", "IT"))
                .when().post(PARAM_TYPE)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /parameter-type/{id} — @RolesAllowed(SYSADMIN). anon -> 401; admin -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deleteParamType_rejectsAnonymousWith401() {
        given().when().delete(PARAM_TYPE + "999999")
                .then().statusCode(401);
    }

    @Test
    void deleteParamType_forbidsAdminWith403() {
        as(accessToken("admin")).when().delete(PARAM_TYPE + "999999")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /upload-param-types — @RolesAllowed(SYSADMIN), multipart. anon (with a part) -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadParamTypes_rejectsAnonymousWith401() {
        given().multiPart("file", "x.json", new byte[]{1})
                .when().post(UPLOAD_PARAM_TYPES)
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /execute — @RolesAllowed(USER). anon -> 401 (container rejects before the handler runs).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void execute_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(Map.of())
                .when().put(EXECUTE)
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /validate-light-character — @PermitAll. Echoes value; isValid reflects the parser.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void validateLightCharacter_validInputReportsValidTrue() {
        given().contentType(ContentType.JSON).body(Map.of("value", "Iso G 4s"))
                .when().post(VALIDATE_LIGHT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("value", is("Iso G 4s"))
                .body("isValid", is(true));
    }

    @Test
    void validateLightCharacter_garbageInputReportsValidFalse() {
        given().contentType(ContentType.JSON).body(Map.of("value", "not a light zzz"))
                .when().post(VALIDATE_LIGHT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("isValid", is(false));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /validate-call-sign — @PermitAll. Echoes the upper-cased value; isValid reflects the parser.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void validateCallSign_validInputReportsValidTrue() {
        given().contentType(ContentType.JSON).body(Map.of("value", "OUXR2"))
                .when().post(VALIDATE_CALLSIGN)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("value", is("OUXR2"))
                .body("isValid", is(true));
    }
}
