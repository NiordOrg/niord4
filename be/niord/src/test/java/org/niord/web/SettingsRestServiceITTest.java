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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for {@link SettingsRestService}: the HTTP contract of the {@code /rest/settings}
 * endpoints, with emphasis on the authorization matrix. The resource is class-annotated
 * {@code @RolesAllowed(SYSADMIN)}; {@code GET /editable-settings} relaxes this to {@code @PermitAll}
 * and then re-checks the {@code sysadmin} role programmatically, and {@code GET /web} is genuinely
 * anonymous (web=true settings are public by definition). The {@code GET}/{@code PUT setting/{key}}
 * and {@code POST upload-settings} endpoints inherit the class-level {@code sysadmin} requirement.
 *
 * <p>Two distinct "denied" status codes are therefore asserted precisely:
 * <ul>
 *   <li>An unauthenticated call to a container-secured ({@code @RolesAllowed}) endpoint -&gt; {@code 401}.</li>
 *   <li>An unauthenticated call to the {@code @PermitAll} {@code editable-settings} endpoint passes the
 *       container but fails the programmatic {@code isCallerInRole(SYSADMIN)} check -&gt; {@code 403}.</li>
 * </ul>
 * The cached {@code sysadmin} token (see {@code accessToken()}) carries the {@code sysadmin} role
 * from {@code niord-client-master}, so it satisfies every endpoint here without a {@code NiordDomain}
 * header.
 *
 * <p>Mutating endpoints ({@code PUT setting/{key}}) run in the server's own transaction and therefore
 * commit (which {@code @Transactional} cannot roll back). Settings live in a small shared key/value
 * space with no create/delete REST endpoint, so the few update round-trips here read the original value
 * first and restore it afterwards rather than leaving the seeded settings mutated. Most cases are
 * read-only or auth-only assertions to avoid polluting shared settings state.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SettingsRestServiceITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    SettingsService settingsService;

    /**
     * Stable editable, String-typed setting. The fixture holds no Setting row since C4b: the IT start writes the
     * settings from its class path, the test tree's niord.json first, then the base product's settings file.
     */
    private static final String SEED_STRING_KEY = "documentationUrl";
    private static final String SEED_STRING_VALUE = "http://docs.niord.org";

    /** Stable seeded, editable, Boolean-typed setting. */
    private static final String SEED_BOOLEAN_KEY = "wmsLayerEnabled";

    /** Stable seeded Password-typed setting whose value must never be emitted in a VO. */
    private static final String SEED_PASSWORD_KEY = "wmsPassword";

    /**
     * Stable setting reachable via the by-key GET that is not on the Settings page: authServerUrl is deployment
     * configuration, which the catalog does not make editable.
     */
    private static final String SEED_NON_EDITABLE_KEY = "authServerUrl";

    /**
     * The deployment configuration that left the Settings page: still declared settings, set by an environment
     * variable or system property of the key's name, the settings file or an existing row, but neither listed nor
     * saved on the page.
     */
    private static final List<String> DEPLOYMENT_KEYS = List.of("batchJobRootPath", "messageIndexPath",
            "publicationPreviewRootPath", "vipsPath", "authServerUrl");

    /** Reads the current value of a setting through the sysadmin-protected GET endpoint. */
    private Object getSettingValue(String key) {
        return given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + key)
                .then().statusCode(200)
                .extract().path("value");
    }

    /** Updates a setting to the given (String) value through the sysadmin-protected PUT endpoint. */
    private void putSettingValue(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/settings/setting/" + key)
                .then().statusCode(200)
                .body("key", is(key));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — unauthenticated requests
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editableSettingsWithoutTokenIsForbidden() {
        // @PermitAll lets the request past the container, but the programmatic isCallerInRole(SYSADMIN)
        // check fails for the anonymous caller and throws NiordWebException(403).
        given()
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(403);
    }

    @Test
    void getSettingWithoutTokenIsUnauthorized() {
        // setting/{key} has no @PermitAll override, so the class-level @RolesAllowed(SYSADMIN) rejects
        // the anonymous caller at the container with 401 (not 403).
        given()
                .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(401);
    }

    @Test
    void updateSettingWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("key", SEED_STRING_KEY, "value", "should-not-apply"))
                .when().put("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(401);
    }

    @Test
    void uploadSettingsWithoutTokenIsUnauthorized() {
        given().multiPart("data", "settings.json", "[]".getBytes())
                .when().post("/rest/settings/upload-settings")
                .then().statusCode(401);
    }

    /** A required key is refused a null value with 400, and its row is unchanged */
    @Test
    void updateARequiredSettingToNullIsRefusedWith400() {
        Object stored = given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/country")
                .then().statusCode(200)
                .extract().path("value");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "country");
        body.put("value", null);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/settings/setting/country")
                .then().statusCode(400)
                .body("detail", is("country is required: it takes a value, not an empty one"));

        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/country")
                .then().statusCode(200)
                .body("value", is(stored));
    }

    @Test
    void updateSettingWithoutTokenDoesNotChangeValue() {
        // Security side-effect: the rejected (401) update must leave the seeded value untouched.
        given().contentType(ContentType.JSON)
                .body(Map.of("key", SEED_STRING_KEY, "value", "IT-REST-Tampered"))
                .when().put("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(401);

        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(200)
                .body("value", is(SEED_STRING_VALUE));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /editable-settings — sysadmin happy path
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editableSettingsAsSysadminReturnsEditableSettings() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("key", hasItem(SEED_STRING_KEY))
                .body("key", hasItem(SEED_BOOLEAN_KEY));
    }

    @Test
    void editableSettingsContainOnlyEditableSettings() {
        // getSettings() -> SettingsService.getAllEditable(): the rows whose key the catalog makes editable.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("key", hasItem(SEED_STRING_KEY))
                .body("key", hasItem(SEED_BOOLEAN_KEY))
                .body("key", not(hasItem(SEED_NON_EDITABLE_KEY)));
    }

    /**
     * A saved setting takes effect at once, so a value the code that reads it cannot use is refused with 400 and the
     * reason, and the row is unchanged: a number of days below 1 (every batch job's files deleted at the next hourly
     * pass, every firing exercise cancelled at the next run) and a map image size or zoom level the drawing does not
     * accept (every new map image the placeholder). The bounds themselves are saved.
     */
    @Test
    void aValueTheCodeCannotUseIsRefusedWith400AndLeavesTheRow() {
        Map<String, List<Object>> refused = new LinkedHashMap<>();
        refused.put("batchFileExpiryDays", List.of(0, -3));
        refused.put("firingExerciseScheduleDays", List.of(0, -1));
        refused.put("mapImageSize", List.of(0, 2049));
        refused.put("mapImageZoomLevel", List.of(-1, 31));
        refused.forEach((key, values) -> {
            Object stored = getSettingValue(key);
            for (Object value : values) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("key", key);
                body.put("value", value);
                given().auth().oauth2(accessToken())
                        .contentType(ContentType.JSON)
                        .body(body)
                        .when().put("/rest/settings/setting/" + key)
                        .then().statusCode(400)
                        .body("detail", is(key + " takes " + switch (key) {
                            case "mapImageSize" -> "a size in pixels from 1 to 2048";
                            case "mapImageZoomLevel" -> "a zoom level from 0 to 30";
                            default -> "a number of days of at least 1";
                        } + ", not " + value));
            }
            assertEquals(stored, getSettingValue(key), key + ": the row is unchanged");
        });

        Object stored = getSettingValue("batchFileExpiryDays");
        try {
            putSettingValue("batchFileExpiryDays", 1);
            assertEquals(1, getSettingValue("batchFileExpiryDays"), "the lowest number of days is saved");
        } finally {
            putSettingValue("batchFileExpiryDays", stored);
        }
    }

    /**
     * A row whose type is String although the catalogue's is Integer, as a database restored from an older system can
     * hold, takes text, and the code parses it: so text that is a number outside the range, and text that is no
     * number, is refused as the number is. FAILS when the range is checked on numbers only: "0" is saved, and every
     * batch job's files are deleted at the next hourly pass.
     */
    @Test
    void aNumberSavedAsTextInARowTypedStringIsHeldToTheSameRange() {
        Map<String, List<String>> refused = new LinkedHashMap<>();
        refused.put("batchFileExpiryDays", List.of("0", "-3", "seven"));
        refused.put("firingExerciseScheduleDays", List.of("0", "1.5"));
        refused.put("mapImageSize", List.of("2049", "x"));
        refused.put("mapImageZoomLevel", List.of("-1", "31"));
        refused.forEach((key, values) -> {
            Setting.Type type = Tx.requiringNew().call(() -> em.find(Setting.class, key).getType());
            Object stored = getSettingValue(key);
            Tx.requiringNew().run(() -> em.find(Setting.class, key).setType(Setting.Type.String));
            try {
                for (String value : values) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("key", key);
                    body.put("value", value);
                    given().auth().oauth2(accessToken())
                            .contentType(ContentType.JSON)
                            .body(body)
                            .when().put("/rest/settings/setting/" + key)
                            .then().statusCode(400)
                            .body("detail", containsString(key + " takes "));
                }
                assertEquals(stored, getSettingValue(key), key + ": the row is unchanged");
            } finally {
                Tx.requiringNew().run(() -> em.find(Setting.class, key).setType(type));
            }
        });

        String key = "batchFileExpiryDays";
        Setting.Type type = Tx.requiringNew().call(() -> em.find(Setting.class, key).getType());
        Object stored = getSettingValue(key);
        Tx.requiringNew().run(() -> em.find(Setting.class, key).setType(Setting.Type.String));
        try {
            putSettingValue(key, "2");
            assertEquals("2", getSettingValue(key), "text in the range is saved");
        } finally {
            // Saved back through the page, so the field and the cache follow
            Tx.requiringNew().run(() -> em.find(Setting.class, key).setType(type));
            putSettingValue(key, stored);
        }
    }

    /**
     * Deployment configuration is not on the Settings page and a save of it is refused with 400, even where its row
     * says editable, as a restored database's rows do (they were written when the keys were still editable, and a
     * repository change never changes a deployment's rows): the page and the save take "editable" from the catalog,
     * as the page takes the description. The rows are not changed.
     */
    @Test
    void deploymentConfigurationIsNeitherListedNorSavedEvenWhereItsRowSaysEditable() {
        Map<String, Boolean> editableBefore = new LinkedHashMap<>();
        Map<String, Object> valueBefore = new LinkedHashMap<>();
        Tx.requiringNew().run(() -> DEPLOYMENT_KEYS.forEach(key -> {
            Setting row = em.find(Setting.class, key);
            assertTrue(row != null, "start-up added " + key);
            editableBefore.put(key, row.isEditable());
            valueBefore.put(key, row.getValue());
            row.setEditable(true);
        }));
        try {
            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/editable-settings")
                    .then().statusCode(200)
                    .body("key", hasItem(SEED_STRING_KEY))
                    .body("key", not(hasItem(DEPLOYMENT_KEYS.get(0))))
                    .body("key", not(hasItem(DEPLOYMENT_KEYS.get(1))))
                    .body("key", not(hasItem(DEPLOYMENT_KEYS.get(2))))
                    .body("key", not(hasItem(DEPLOYMENT_KEYS.get(3))))
                    .body("key", not(hasItem(DEPLOYMENT_KEYS.get(4))));

            for (String key : DEPLOYMENT_KEYS) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("key", key);
                body.put("value", "/it-refused");
                given().auth().oauth2(accessToken())
                        .contentType(ContentType.JSON)
                        .body(body)
                        .when().put("/rest/settings/setting/" + key)
                        .then().statusCode(400)
                        .body("detail", containsString("deployment configuration"));
            }
            Tx.requiringNew().run(() -> DEPLOYMENT_KEYS.forEach(key -> {
                Setting row = em.find(Setting.class, key);
                assertEquals(valueBefore.get(key), row.getValue(), key + ": the row's value is unchanged");
                assertTrue(row.isEditable(), key + ": the row's own flag is not changed either");
            }));
        } finally {
            Tx.requiringNew().run(() -> DEPLOYMENT_KEYS.forEach(key ->
                    em.find(Setting.class, key).setEditable(editableBefore.get(key))));
        }
    }

    /**
     * The page lists only the editable settings the code declares (owner, 2026-10-21; docs/code-data-config-plan.md
     * §5.6): an editable row no catalog declares, a legacy authServerRealmKey and a row of its own, is left out, and
     * stays in the database untouched. The old IT seed carried the legacy row (an older deployment's database holds
     * it); the fixture has no Setting row since C4b, so the test plants it and removes it afterwards.
     */
    @Test
    void editableSettingsLeaveOutARowNoCatalogDeclares() {
        String key = "it-undeclared-editable-" + System.nanoTime();
        Tx.requiringNew().run(() -> em.persist(new Setting(key, "x", "An undeclared row", false, false, true)));
        boolean plantedLegacy = Tx.requiringNew().call(() -> {
            if (em.find(Setting.class, "authServerRealmKey") != null) {
                return false;
            }
            em.persist(new Setting("authServerRealmKey", "niord", "The Keycloak realm key", false, false, true));
            return true;
        });
        try {
            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/editable-settings")
                    .then().statusCode(200)
                    .body("key", hasItem(SEED_STRING_KEY))
                    .body("key", not(hasItem(key)))
                    .body("key", not(hasItem("authServerRealmKey")));

            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/setting/" + key)
                    .then().statusCode(200)
                    .body("value", is("x"));
            Setting legacy = Tx.requiringNew().call(() -> em.find(Setting.class, "authServerRealmKey"));
            assertTrue(legacy != null && legacy.isEditable(), "the legacy row is still in the database");
        } finally {
            Tx.requiringNew().run(() -> em.createQuery("delete from Setting where key = :k")
                    .setParameter("k", key).executeUpdate());
            if (plantedLegacy) {
                Tx.requiringNew().run(() -> em.createQuery("delete from Setting where key = :k")
                        .setParameter("k", "authServerRealmKey").executeUpdate());
            }
        }
    }

    @Test
    void editableSettingsCarryDescriptionAndType() {
        // The VO copies through the description and type of each editable setting.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("find { it.key == '" + SEED_STRING_KEY + "' }.type", is("String"))
                .body("find { it.key == '" + SEED_STRING_KEY + "' }.description", notNullValue())
                .body("find { it.key == '" + SEED_BOOLEAN_KEY + "' }.type", is("Boolean"));
    }

    /**
     * The Settings page shows a declared key's description from the catalogue, the code's current text, and not the
     * text its row was first written with: a row keeps that text, so a changed description would otherwise reach only
     * new deployments (SECOM-UI-1, SETTINGS-12; the owner's answer to question 3 of P2, 2026-11-15). Only the answer
     * changes: the row keeps its own description, and the value and type still come from the row.
     */
    @Test
    void editableSettingsShowTheCataloguesDescriptionNotTheRowsOldOne() {
        String key = "wmsProtected";
        String oldText = "Whether the API requires an authenticated user";
        String rowBefore = Tx.requiringNew().call(() -> em.find(Setting.class, key).getDescription());
        Tx.requiringNew().run(() -> em.find(Setting.class, key).setDescription(oldText));
        try {
            String catalogue = settingsService.catalogEntry(key).description();
            assertEquals("Whether the WMS map tiles (/wms/*) require a signed-in user", catalogue,
                    "the catalogue's description names the map tiles");
            Setting row = Tx.requiringNew().call(() -> em.find(Setting.class, key));

            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/editable-settings")
                    .then().statusCode(200)
                    .body("find { it.key == '" + key + "' }.description", is(catalogue))
                    .body("find { it.key == '" + key + "' }.type", is(row.getType().name()))
                    .body("find { it.key == '" + key + "' }.value", is(row.getValue()));

            assertEquals(oldText, Tx.requiringNew().call(() -> em.find(Setting.class, key).getDescription()),
                    "the row keeps its own description: only the answer changes");
        } finally {
            Tx.requiringNew().run(() -> em.find(Setting.class, key).setDescription(rowBefore));
        }
    }

    @Test
    void getSettingByKeyMasksPasswordValue() {
        // SECAUD-2 fix: the by-key endpoint now builds the VO via SettingVo(Setting), which — like the
        // editable-settings list — never emits Password-typed values. The key and type are still served.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_PASSWORD_KEY)
                .then().statusCode(200)
                .body("key", is(SEED_PASSWORD_KEY))
                .body("type", is("Password"))
                .body("value", is(nullValue()));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /web — anonymous flat map of web=true settings
    // ------------------------------------------------------------------------------------------- //

    @Test
    void webSettingsAreServedAnonymously() {
        // Unlike editable-settings (@PermitAll + programmatic sysadmin re-check -> 403), the /web
        // endpoint is anonymous by design: web=true marks a setting as publicly shippable to the
        // browser.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("size()", greaterThan(0));
    }

    @Test
    void webSettingsReturnSeededWebSettingsAsFlatTypedMap() {
        // The response is a flat { key: value } map (no VO wrapper), and values keep their
        // JSON types: String, Boolean and Integer settings the IT start writes from the test tree's niord.json.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body(SEED_STRING_KEY, is(SEED_STRING_VALUE))
                .body(SEED_BOOLEAN_KEY, is(true))
                .body("mapDefaultZoomLevel", is(6));
    }

    /**
     * Whether the administration app's maps start with the sea-chart layer on ({@code map.wmsLayerEnabledByDefault} in
     * the app, which takes the key of the flat map as a JSON boolean or the text "true"). Start-up adds the row, off;
     * the Settings page lists it; and once that row holds true, as the Settings page leaves it, it is served as the
     * JSON boolean true. Before the key was declared, no catalog named it: start-up added no row, and /web left out a
     * row written by hand. A row written by hand before then is kept with its own flags
     * ({@code SettingsAddMissingITTest.aRowMadeByHandIsAdoptedWithItsOwnFlags}).
     */
    @Test
    void theSeaChartLayersStartIsAWebSettingTheSettingsPageEdits() {
        String key = "wmsLayerEnabledByDefault";
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + key)
                .then().statusCode(200)
                .body("value", is(false));
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("find { it.key == '" + key + "' }.type", is("Boolean"))
                .body("find { it.key == '" + key + "' }.description", notNullValue());
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body(key, is(false));

        Object stored = Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
        Tx.requiringNew().run(() -> em.find(Setting.class, key).setValue(true));
        try {
            given()
                    .when().get("/rest/settings/web")
                    .then().statusCode(200)
                    .body(key, is(true))
                    .body("wmsLayerEnabled", is(true));
        } finally {
            Tx.requiringNew().run(() -> em.find(Setting.class, key).setValue(stored));
        }
    }

    @Test
    void webSettingsCarryThePublicSiteLanguagesAsTheSiteReadsThem() {
        // Not in the seed: start-up adds them (SettingsService.addMissing) from the test tree's root
        // niord.json, read as the country's settings file, as it adds every catalog key a database lacks.
        // The public site reads the list as a JSON array, not as the text of one.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("infoSiteLanguages", is(List.of("da", "en")))
                .body("infoDefaultLanguage", is("da"));
    }

    @Test
    void webSettingsCarryTheTileAddressesButNotTheTileContact() {
        // Not in the seed: start-up adds them from the test tree's root niord.json, which points both
        // addresses at a port nothing listens on. Both web applications read the addresses from here; the
        // contact goes only into the User-Agent of the backend's own tile requests.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("osmUrl", is("http://127.0.0.1:9/{z}/{x}/{y}.png"))
                .body("openSeaMapUrl", is("http://127.0.0.1:9/{z}/{x}/{y}.png"))
                .body("$", not(hasKey("mapTileContact")));
    }

    @Test
    void theTileSettingsAreEditableBySysadmin() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("key", hasItem("osmUrl"))
                .body("key", hasItem("openSeaMapUrl"))
                .body("key", hasItem("mapTileContact"))
                .body("find { it.key == 'mapTileContact' }.value", is(""));
    }

    @Test
    void theRetiredMapImageSettingsAreGone() {
        // The base-data seed inserts osmSourceUrl, mapImageServer and mapImageIndent after the master
        // changelog has run; the changeset that follows the seed deletes them again, no catalog declares
        // them and no code reads them any more. The web applications read the base-map address from osmUrl,
        // and the server draws its map images on the same tiles.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("$", not(hasKey("osmSourceUrl")))
                .body("$", not(hasKey("mapImageServer")))
                .body("$", not(hasKey("mapImageIndent")));
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200)
                .body("key", not(hasItem("osmSourceUrl")))
                .body("key", not(hasItem("mapImageServer")))
                .body("key", not(hasItem("mapImageIndent")));
    }

    @Test
    void webSettingsCarryTheDeclaredMode() {
        // The deployment ribbon of both frontends follows it; a database nobody configured is production
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("mode", is("production"));
    }

    @Test
    void webSettingsCarryTheComputedS124AndSecomSwitches() {
        // Computed from the running properties, never stored: S-124 is on by default, SECOM off
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("s124Enabled", is(true))
                .body("secomEnabled", is(false));
    }

    @Test
    void webSettingsOmitNonWebSettings() {
        // authServerUrl is seeded with web=false and must not leak into the public map.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body(SEED_NON_EDITABLE_KEY, is(nullValue()));
    }

    @Test
    void webSettingsOmitPasswordTypedSettings() {
        // wmsPassword is Password-typed (and web=false in the seed). The endpoint additionally
        // guards against Password-typed settings ever being flagged web=true; with no such seed
        // row the type-guard is not independently exercisable, so this pins the absence invariant.
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body(SEED_PASSWORD_KEY, is(nullValue()));
    }

    @Test
    void webSettingsAlsoServedToAuthenticatedCallers() {
        // @PermitAll must not reject authenticated callers either - the same map is returned.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body(SEED_STRING_KEY, is(SEED_STRING_VALUE));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /setting/{key} — sysadmin happy path and edge cases
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getSettingAsSysadminReturnsSeededStringValue() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(200)
                .body("key", is(SEED_STRING_KEY))
                .body("value", is(SEED_STRING_VALUE));
    }

    @Test
    void getSettingAsSysadminReturnsSeededBooleanValue() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_BOOLEAN_KEY)
                .then().statusCode(200)
                .body("key", is(SEED_BOOLEAN_KEY))
                .body("value", is(true));
    }

    @Test
    void getSettingReadsNonEditableSettingByKey() {
        // The by-key read is not restricted to editable settings: a setting is returned with its
        // persisted value. The IT start writes authServerUrl as http://localhost:8080/auth, the value
        // of the test tree's niord.json and of the base product's settings file alike (the old IT
        // seed stored "/auth"), so the test still verifies the round-trip of the stored value.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_NON_EDITABLE_KEY)
                .then().statusCode(200)
                .body("key", is(SEED_NON_EDITABLE_KEY))
                .body("value", is("http://localhost:8080/auth"));
    }

    @Test
    void getSettingForUnknownKeyReturns404() {
        // SECAUD-2 fix: an unknown key is rejected with 404 instead of the old behavior of consulting
        // system properties / environment variables and persisting a fresh Setting row on read.
        String unknownKey = "IT-REST-unknown-setting-" + System.nanoTime();
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + unknownKey)
                .then().statusCode(404);

        // No insert-on-read: the key was not created by the lookup, so a repeated read still misses.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + unknownKey)
                .then().statusCode(404);
    }

    @Test
    void getSettingDoesNotExposeEnvironmentVariables() {
        // SECAUD-2 fix: before the fix, settingsService.get(key) returned System.getenv(key) for ANY
        // key before consulting the DB, so a sysadmin could read arbitrary host secrets (DB_PASSWORD,
        // AWS_SECRET_ACCESS_KEY, ...) by key. PATH is set in every server environment and must not be
        // served as a setting.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/PATH")
                .then().statusCode(404);
    }

    @Test
    void getSettingDoesNotExposeSystemProperties() {
        // SECAUD-2 fix: same leak vector via System.getProperty(key) — java.version is always set in
        // the server JVM and must not be served as a setting.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/java.version")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /setting/{key} — validation and update round-trips (value restored afterwards)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateSettingWithMismatchedKeyReturns400() {
        // Path key differs from the body key -> NiordWebException(400), before any DB change.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", SEED_STRING_KEY + "-OTHER", "value", "IT-REST-Mismatch"))
                .when().put("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(400);

        // The rejected update left the seeded value untouched.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(200)
                .body("value", is(SEED_STRING_VALUE));
    }

    @Test
    void updateNonExistingSettingReturns400() {
        // SettingsService.set() throws IllegalArgumentException for a key no catalog declares and for a key
        // with no DB row, which RestExceptionStatusAdvice maps to 400 Bad Request (ERR1 fix) rather than
        // silently creating the setting (previously this surfaced as a 500).
        String ghostKey = "IT-REST-ghost-setting-" + System.nanoTime();
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", ghostKey, "value", "IT-REST-Ghost"))
                .when().put("/rest/settings/setting/" + ghostKey)
                .then().statusCode(400);
    }

    @Test
    void updateSettingPersistsNewValueAndIsReadBack() {
        Object original = getSettingValue(SEED_STRING_KEY);
        try {
            String newValue = "http://docs.niord.org/IT-REST-Updated";
            given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body(Map.of("key", SEED_STRING_KEY, "value", newValue))
                    .when().put("/rest/settings/setting/" + SEED_STRING_KEY)
                    .then().statusCode(200)
                    .body("key", is(SEED_STRING_KEY))
                    .body("value", is(newValue));

            // Persistence side-effect: the new value is durable and visible through a fresh GET.
            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                    .then().statusCode(200)
                    .body("value", is(newValue));
        } finally {
            // Restore the seeded value so the shared settings space is left unchanged for other tests.
            putSettingValue(SEED_STRING_KEY, original);
        }

        // The restore really took effect.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_STRING_KEY)
                .then().statusCode(200)
                .body("value", is(SEED_STRING_VALUE));
    }

    @Test
    void updateBooleanSettingTogglesPersistedValue() {
        Object original = getSettingValue(SEED_BOOLEAN_KEY);
        try {
            given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body(Map.of("key", SEED_BOOLEAN_KEY, "value", false))
                    .when().put("/rest/settings/setting/" + SEED_BOOLEAN_KEY)
                    .then().statusCode(200)
                    .body("value", is(false));

            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/setting/" + SEED_BOOLEAN_KEY)
                    .then().statusCode(200)
                    .body("value", is(false));
        } finally {
            putSettingValue(SEED_BOOLEAN_KEY, original);
        }

        // Confirm the original boolean value was restored.
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/" + SEED_BOOLEAN_KEY)
                .then().statusCode(200)
                .body("value", is(true));
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /setting/{key} — refusals (docs/code-data-config-plan.md §5.3): 400, the row unchanged
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStateSettingReturns400() {
        // Only the system writes a state setting (SettingsService.writeState)
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", "areaLastUpdate", "value", 1))
                .when().put("/rest/settings/setting/areaLastUpdate")
                .then().statusCode(400);
    }

    @Test
    void updateRowNoCatalogDeclaresReturns400AndLeavesTheRow() {
        // A row of its own that no code declares or reads, committed so the server sees it, deleted afterwards
        String key = "it-undeclared-" + System.nanoTime();
        Tx.requiringNew().run(() -> em.persist(new Setting(key, "x")));
        try {
            given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body(Map.of("key", key, "value", "IT-REST-Changed"))
                    .when().put("/rest/settings/setting/" + key)
                    .then().statusCode(400);

            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/setting/" + key)
                    .then().statusCode(200)
                    .body("value", is("x"));
        } finally {
            Tx.requiringNew().run(() -> em.createQuery("delete from Setting where key = :k")
                    .setParameter("k", key).executeUpdate());
        }
    }

    @Test
    void updateWithAValueOfTheWrongTypeReturns400AndLeavesTheRow() {
        Object original = getSettingValue("mapDefaultZoomLevel");
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", "mapDefaultZoomLevel", "value", "x"))
                .when().put("/rest/settings/setting/mapDefaultZoomLevel")
                .then().statusCode(400);

        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/setting/mapDefaultZoomLevel")
                .then().statusCode(200)
                .body("value", is(original));
    }

    /**
     * A number setting the Settings page empties (its drawer sends null) is saved empty, and a live read gives its
     * default at once, as an injected field does after the next start (the owner's ruling). Before, the live
     * read gave null until a restart.
     */
    @Test
    void anEmptiedNumberSettingIsSavedEmptyAndReadsAsItsDefault() {
        String key = "mapImageSize";
        Object original = getSettingValue(key);
        Object expected = settingsService.resolveDefault(new Setting(key));
        try {
            putSettingValue(key, null);

            given().auth().oauth2(accessToken())
                    .when().get("/rest/settings/setting/" + key)
                    .then().statusCode(200)
                    .body("value", is(nullValue()));
            assertEquals(((Number) expected).intValue(), settingsService.getInteger(key),
                    "the live read gives the default");
        } finally {
            putSettingValue(key, original);
        }
        assertEquals(original, getSettingValue(key), "the value is put back");
    }
}
