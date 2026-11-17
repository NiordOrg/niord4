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
package org.niord.secom;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.settings.Setting;
import org.niord.secom.model.S124SeriesConfig;
import org.niord.secom.service.SecomConfigService;
import org.niord.test.Tx;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for {@link SecomConfigService} — the persisted per-series SECOM exposure rows behind
 * {@code S124DatasetService} eligibility (no row = not exposed, closed by default; replaced
 * the {@code secomS124Series} setting in phase 3 of docs/s124-cancellation-plan.md), with
 * the per-series S-124 language restriction they carry — and
 * for the phase-4 settings migration: the scalar SECOM knobs are ordinary {@code @Setting}s,
 * auto-registered with their defaults by the consuming beans at startup.
 */
class SecomConfigITTest extends SecomITTest {

    // ------------------------------------------------------------------------------------------- //
    // Per-series exposure
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void exposedSeriesIdsReflectTheConfigRows() {
        assertEquals(Set.of("dma-nw"), secomConfigService.exposedSeriesIds(), "the base setup exposes dma-nw");

        secomConfigService.upsertSeriesConfig("dma-nm", true, List.of());
        assertEquals(Set.of("dma-nw", "dma-nm"), secomConfigService.exposedSeriesIds());

        secomConfigService.upsertSeriesConfig("dma-nm", false, List.of());
        assertEquals(Set.of("dma-nw"), secomConfigService.exposedSeriesIds(),
                "an exposeOverSecom=false row does not expose the series");
        assertEquals(2, secomConfigService.getSeriesConfigs().size(),
                "flipping the flag updates the existing row rather than duplicating it");

        secomConfigService.deleteSeriesConfig("dma-nw");
        assertEquals(Set.of(), secomConfigService.exposedSeriesIds(),
                "no exposed rows - nothing is published over SECOM (closed by default)");
    }

    @Test
    @Transactional
    void languageRestrictionIsStoredCanonicallyAndClearedByAnEmptyList() {
        // The seeded model languages are ["da", "en"]: the request order is not kept, the
        // model-language order is
        secomConfigService.upsertSeriesConfig("dma-nw", true, List.of("EN", "da", "en"));
        assertEquals(List.of("da", "en"), secomConfigService.findSeriesConfig("dma-nw").getLanguages());
        assertEquals("da,en", storedLanguages("dma-nw"), "persisted as one comma-separated column");

        secomConfigService.upsertSeriesConfig("dma-nw", true, List.of("en"));
        assertEquals(List.of("en"), secomConfigService.findSeriesConfig("dma-nw").getLanguages());

        // The row is replaced as given: the exposure flag and the languages travel together
        secomConfigService.upsertSeriesConfig("dma-nw", false, List.of("en"));
        S124SeriesConfig config = secomConfigService.findSeriesConfig("dma-nw");
        assertFalse(config.isExposeOverSecom());
        assertEquals(List.of("en"), config.getLanguages());

        secomConfigService.upsertSeriesConfig("dma-nw", true, List.of());
        assertEquals(List.of(), secomConfigService.findSeriesConfig("dma-nw").getLanguages());
        assertNull(storedLanguages("dma-nw"), "no restriction is stored as NULL, not as an empty string");
        assertEquals(1, secomConfigService.getSeriesConfigs().size(), "still one row for the series");
    }

    @Test
    @Transactional
    void aNewRowStartsWithoutALanguageRestriction() {
        S124SeriesConfig created = secomConfigService.upsertSeriesConfig("dma-nm", true, null);
        assertEquals(List.of(), created.getLanguages());
        assertNull(storedLanguages("dma-nm"));
    }

    @Test
    @Transactional
    void upsertRejectsALanguageOutsideTheModelLanguages() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> secomConfigService.upsertSeriesConfig("dma-nw", true, List.of("en", "fr")));
        assertTrue(e.getMessage().contains("'fr'"), e.getMessage());
        assertEquals(List.of(), secomConfigService.findSeriesConfig("dma-nw").getLanguages(),
                "a rejected request leaves the row untouched");
    }

    /** The raw "languages" column of the series' config row (null when unrestricted) */
    private String storedLanguages(String seriesId) {
        return (String) em.createNativeQuery(
                        "select c.languages from \"S124SeriesConfig\" c "
                        + " join \"MessageSeries\" s on s.id = c.series_id where s.\"seriesId\" = :seriesId")
                .setParameter("seriesId", seriesId)
                .getSingleResult();
    }

    @Test
    @Transactional
    void upsertRejectsUnknownSeries() {
        assertThrows(IllegalArgumentException.class,
                () -> secomConfigService.upsertSeriesConfig("no-such-series", true, List.of()),
                "config rows can only be created for existing message series");
    }

    @Test
    @Transactional
    void deleteRejectsSeriesWithoutConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> secomConfigService.deleteSeriesConfig("dma-nm"),
                "only existing config rows can be deleted");
    }

    @Test
    @Transactional
    void datasetEligibilityReadsTheSeriesConfig() {
        Message published = publishNw("secom-config-elig-" + uniq(), 55.5, 11.1);
        assertTrue(datasetService.isEligible(published), "dma-nw is exposed by the base setup");
        assertEquals(Set.of("dma-nw"), datasetService.scopedSeriesIds());

        secomConfigService.upsertSeriesConfig("dma-nw", false, List.of());
        assertFalse(datasetService.isEligible(published), "clearing the exposure closes the series");
    }

    // ------------------------------------------------------------------------------------------- //
    // Settings migration (phase 4)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theSeriesSettingStaysRetiredAndTheScalarKnobsAreSettings() {
        Tx.requiringNew().run(() -> {
            Long count = em.createQuery(
                            "select count(s) from Setting s where s.key = 'secomS124Series'", Long.class)
                    .getSingleResult();
            assertEquals(0, count,
                    "secomS124Series was replaced by the S124SeriesConfig entity and must no longer be registered");

            // The scalar SECOM knobs are ordinary settings (phase 4 dropped the SecomConfig
            // singleton), auto-registered by the consuming beans when the SECOM context starts
            List<String> keys = em.createQuery(
                            "select s.key from Setting s where s.key like 'secom%'", String.class)
                    .getResultList();
            assertTrue(keys.containsAll(List.of(
                            "secomSignatureAlgorithm", "secomValidateClientChain",
                            "secomRevocationCheck", "secomServiceRegistryUrl", "secomServiceRegistryInsecure",
                            "secomCallbackEndpoint", "secomEnabled")),
                    "expected the migrated SECOM settings to be registered, found: " + keys);
            assertFalse(keys.contains("secomS124Language"),
                    "the single-language setting is gone: datasets carry every model language the message "
                    + "has content in, narrowed per series by S124SeriesConfig.languages");
        });
    }

    @Test
    void migratedSecomSettingsCarryTheirDefaults() {
        Tx.requiringNew().run(() -> {
            assertEquals("SHA2_384_WITH_ECDSA", settingValue("secomSignatureAlgorithm"));
            assertEquals(Boolean.TRUE, settingValue("secomValidateClientChain"));
            assertEquals(Boolean.TRUE, settingValue("secomRevocationCheck"));
            assertEquals(Boolean.FALSE, settingValue("secomServiceRegistryInsecure"));
        });
    }

    @Test
    void theSecomEnabledFlagIsAWebSetting() {
        // Registered (default true) exactly when the SECOM subsystem is enabled; web=true so
        // /rest/settings/web ships it to the frontend menu
        Tx.requiringNew().run(() -> {
            Setting flag = em.createQuery("select s from Setting s where s.key = 'secomEnabled'",
                            Setting.class)
                    .getSingleResult();
            assertEquals(Boolean.TRUE, flag.getValue());
            assertTrue(flag.isWeb(), "must be web-exposed so the frontend menu can read it");
            // The subsystem is governed by niord.secom.enabled alone; an editable row would let a
            // sysadmin toggle the SIGNAL to false (hiding the menu) while SECOM keeps running
            assertFalse(flag.isEditable(), "must not appear as a toggle on the generic Settings page");
        });
    }

    @Test
    void theWebSettingsReportSecomAsRunning() {
        // /rest/settings/web computes both switches from the running properties: SECOM runs here,
        // and SECOM needs S-124
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("secomEnabled", is(true))
                .body("s124Enabled", is(true));
    }

    private Object settingValue(String key) {
        return em.createQuery("select s from Setting s where s.key = :key", Setting.class)
                .setParameter("key", key)
                .getSingleResult()
                .getValue();
    }
}
