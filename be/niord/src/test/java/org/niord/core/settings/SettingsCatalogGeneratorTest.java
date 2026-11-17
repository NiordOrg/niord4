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
package org.niord.core.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings catalog generator ({@link SettingsCatalogGenerator}, docs/code-data-config-plan.md §5.1): one failing
 * declaration per rule, and the catalog it makes. The fixtures are nested classes of this test, never resources under
 * {@code defaults/}, which would ship in the test-jar.
 */
class SettingsCatalogGeneratorTest {

    // ---------------------------------------------------------------- fixtures

    static class Annotated {
        @org.niord.core.settings.annotation.Setting(value = "fixtureZoom", defaultValue = "6",
                type = Setting.Type.Integer, web = true, description = "Zoom")
        Integer zoom;

        @org.niord.core.settings.annotation.Setting(value = "fixtureEnabled", defaultValue = "true",
                type = Setting.Type.Boolean, editable = false, description = "Enabled")
        Boolean enabled;

        @org.niord.core.settings.annotation.Setting(value = "fixtureLanguages", defaultValue = "[\"da\", \"en\"]",
                type = Setting.Type.json, description = "Languages")
        Object languages;

        @org.niord.core.settings.annotation.Setting(description = "Named by the field")
        String fixtureByField;

        @org.niord.core.settings.annotation.Setting(value = "fixtureCountry", defaultValue = "XX", required = true,
                description = "Country")
        String country;
    }

    static class Constants {
        static final Setting MODE = new Setting("fixtureMode", "production")
                .description("Mode")
                .web(true)
                .editable(true);

        static final Setting LAST_UPDATE = new Setting("fixtureLastUpdate", null, false)
                .type(Setting.Type.Date)
                .scope(Setting.Scope.state)
                .description("Last update");

        // Not a declaration: an instance field
        final Setting notStatic = new Setting("fixtureInstance", "x");
    }

    /** Declares fixtureZoom as {@link Annotated} does */
    static class SameZoom {
        @org.niord.core.settings.annotation.Setting(value = "fixtureZoom", defaultValue = "6",
                type = Setting.Type.Integer, web = true, description = "Zoom")
        int zoom;
    }

    static class OtherDefault {
        @org.niord.core.settings.annotation.Setting(value = "fixtureZoom", defaultValue = "7",
                type = Setting.Type.Integer, web = true, description = "Zoom")
        Integer zoom;
    }

    static class OtherDescription {
        @org.niord.core.settings.annotation.Setting(value = "fixtureZoom", defaultValue = "6",
                type = Setting.Type.Integer, web = true, description = "The zoom")
        Integer zoom;
    }

    static class NoType {
        static final Setting NO_TYPE = new Setting("fixtureNoType").description("No value, no type");
    }

    static class WebPassword {
        @org.niord.core.settings.annotation.Setting(value = "fixturePassword", type = Setting.Type.Password,
                web = true, description = "Password")
        String password;
    }

    static class EditableState {
        static final Setting STATE = new Setting("fixtureState", null, false)
                .type(Setting.Type.Date)
                .scope(Setting.Scope.state)
                .editable(true);
    }

    static class WebState {
        static final Setting STATE = new Setting("fixtureState", null, false)
                .type(Setting.Type.Date)
                .scope(Setting.Scope.state)
                .web(true);
    }

    static class RequiredState {
        static final Setting STATE = new Setting("fixtureState", null, false)
                .type(Setting.Type.Date)
                .scope(Setting.Scope.state)
                .required(true);
    }

    static class LongDescription {
        static final Setting LONG = new Setting("fixtureLong", "x").description("d".repeat(256));
    }

    static class BadJson {
        @org.niord.core.settings.annotation.Setting(value = "fixtureBadJson", defaultValue = "[\"da\", ",
                type = Setting.Type.json, description = "Broken")
        Object broken;
    }

    // ---------------------------------------------------------------- the catalog

    @Test
    void annotationDefaultsAreTypedAsTheInjectionParsesThem() {
        Map<String, SettingsCatalog.Entry> catalog = generate(null, Annotated.class).byKey();

        assertEquals(6, catalog.get("fixtureZoom").codeDefault());
        assertEquals(Setting.Type.Integer, catalog.get("fixtureZoom").type());
        assertTrue(catalog.get("fixtureZoom").web());
        assertEquals(Boolean.TRUE, catalog.get("fixtureEnabled").codeDefault());
        assertFalse(catalog.get("fixtureEnabled").editable());
        assertEquals(List.of("da", "en"), catalog.get("fixtureLanguages").codeDefault());
        assertEquals("", catalog.get("fixtureByField").codeDefault(), "a String annotation without a default");
        assertEquals(Setting.Scope.operator, catalog.get("fixtureZoom").scope());
    }

    @Test
    void aConstantsValueFlagsAndScopeAreCarried() {
        Map<String, SettingsCatalog.Entry> catalog = generate(null, Constants.class).byKey();

        assertEquals(List.of("fixtureLastUpdate", "fixtureMode"), List.copyOf(catalog.keySet()),
                "the static constants only, sorted by key");
        SettingsCatalog.Entry mode = catalog.get("fixtureMode");
        assertEquals("production", mode.codeDefault());
        assertEquals(Setting.Type.String, mode.type());
        assertTrue(mode.web() && mode.editable() && mode.cached());
        SettingsCatalog.Entry lastUpdate = catalog.get("fixtureLastUpdate");
        assertEquals(Setting.Scope.state, lastUpdate.scope());
        assertFalse(lastUpdate.cached());
        assertNull(lastUpdate.codeDefault());
    }

    @Test
    void requiredIsCarried() {
        assertTrue(generate(null, Annotated.class).byKey().get("fixtureCountry").required());
        assertFalse(generate(null, Annotated.class).byKey().get("fixtureZoom").required());
    }

    @Test
    void theBaseValueIsTheSettingsFilesValue() {
        SettingsCatalog catalog = SettingsCatalogGenerator.generate(SettingsCatalogGenerator.declarations(Annotated.class),
                Map.of("fixtureZoom", 7), null, "4.0.1", false);

        assertEquals(7, catalog.byKey().get("fixtureZoom").baseValue());
        assertNull(catalog.byKey().get("fixtureEnabled").baseValue());
    }

    @Test
    void agreeingDeclarationsOfOneKeyMakeOneEntry() {
        SettingsCatalog catalog = generate(null, Annotated.class, SameZoom.class);

        assertEquals(1, catalog.entries().stream().filter(e -> e.key().equals("fixtureZoom")).count());
    }

    @Test
    void entriesAreSortedByKey() {
        List<String> keys = generate(null, Constants.class, Annotated.class).entries().stream()
                .map(SettingsCatalog.Entry::key)
                .toList();

        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        assertEquals(sorted, keys);
    }

    @Test
    void aKeyKeepsTheVersionTheCommittedCatalogGivesIt() {
        SettingsCatalog committed = new SettingsCatalog(List.of(new SettingsCatalog.Entry("fixtureMode",
                Setting.Type.String, "production", null, Setting.Scope.operator, true, true, false, true, "4.0.10",
                "Mode", List.of(Constants.class.getName()))));

        Map<String, SettingsCatalog.Entry> catalog = SettingsCatalogGenerator.generate(
                SettingsCatalogGenerator.declarations(Constants.class), Map.of(), committed, "4.0.99", true).byKey();

        assertEquals("4.0.10", catalog.get("fixtureMode").introducedIn());
        assertEquals("4.0.99", catalog.get("fixtureLastUpdate").introducedIn());
    }

    @Test
    void declaredInListsEveryDeclaringClassSorted() {
        Map<String, SettingsCatalog.Entry> catalog = generate(null, SameZoom.class, Annotated.class).byKey();

        assertEquals(List.of(Annotated.class.getName(), SameZoom.class.getName()),
                catalog.get("fixtureZoom").declaredIn(), "both declaring classes, sorted");
        assertEquals(List.of(Annotated.class.getName()), catalog.get("fixtureEnabled").declaredIn());
        assertEquals(List.of(Constants.class.getName()),
                generate(null, Constants.class).byKey().get("fixtureMode").declaredIn(), "a constant's class");
    }

    @Test
    void aCatalogWithoutDeclaredInReadsAsEmpty() {
        SettingsCatalog catalog = SettingsCatalog.parse("""
                { "settings": [ { "key": "fixtureOld", "type": "String", "scope": "operator" } ] }
                """);

        assertEquals(List.of(), catalog.byKey().get("fixtureOld").declaredIn());
    }

    @Test
    void theWrittenFormIsTheSameOnEveryRunAndLfOnly() {
        String once = generate(null, Annotated.class, Constants.class).toJson();
        String twice = generate(null, Constants.class, Annotated.class).toJson();

        assertEquals(once, twice);
        assertFalse(once.contains("\r"));
        assertTrue(once.endsWith("}\n"));
        assertEquals(once, SettingsCatalog.parse(once).toJson(), "reading and writing gives the same text");
        assertTrue(SettingsCatalog.sameText(once.replace("\n", "\r\n"), once), "a CRLF checkout compares equal");
    }

    @Test
    void theDiffNamesAddedRemovedAndChangedKeys() {
        SettingsCatalog before = generate("4.0.1", Constants.class);
        SettingsCatalog after = SettingsCatalogGenerator.generate(SettingsCatalogGenerator.declarations(Annotated.class),
                Map.of("fixtureZoom", 7), before, "4.0.1", false);

        String diff = SettingsCatalogGenerator.diff(before, after);

        assertTrue(diff.contains("added: fixtureZoom"), diff);
        assertTrue(diff.contains("removed: fixtureMode"), diff);
    }

    @Test
    void theDiffNamesAChangedDeclaredIn() {
        SettingsCatalog before = generate("4.0.1", Annotated.class);
        SettingsCatalog after = generate("4.0.1", Annotated.class, SameZoom.class);

        assertTrue(SettingsCatalogGenerator.diff(before, after).contains("changed: fixtureZoom (declaredIn)"),
                SettingsCatalogGenerator.diff(before, after));
    }

    // ---------------------------------------------------------------- the rules

    @Test
    void aDeclarationWithoutATypeFails() {
        assertBreaks("fixtureNoType declares no type", NoType.class);
    }

    @Test
    void declarationsWithDifferentDefaultsFail() {
        String message = assertBreaks("fixtureZoom is declared differently", Annotated.class, OtherDefault.class);
        assertTrue(message.contains("default 6 / 7"), message);
        assertTrue(message.contains(Annotated.class.getName() + "#zoom"), message);
        assertTrue(message.contains(OtherDefault.class.getName() + "#zoom"), message);
    }

    @Test
    void declarationsWithDifferentDescriptionsFail() {
        assertTrue(assertBreaks("fixtureZoom is declared differently", Annotated.class, OtherDescription.class)
                .contains("description"));
    }

    @Test
    void aWebPasswordFails() {
        assertBreaks("fixturePassword is a Password setting and web", WebPassword.class);
    }

    @ParameterizedTest
    @ValueSource(classes = {EditableState.class, WebState.class, RequiredState.class})
    void aWebEditableOrRequiredStateSettingFails(Class<?> fixture) {
        assertBreaks("fixtureState is a state setting and web, editable or required", fixture);
    }

    @Test
    void aDescriptionLongerThanTheColumnFails() {
        String message = assertBreaks("fixtureLong's description has 256 characters", LongDescription.class);
        assertTrue(message.contains(LongDescription.class.getName() + "#LONG"), message);
        assertTrue(message.contains("the Setting.description column holds 255"), message);
    }

    @Test
    void anUnparseableJsonDefaultFails() {
        assertBreaks("fixtureBadJson's json default does not parse", BadJson.class);
    }

    @Test
    void aNewKeyWithoutAVersionFailsTheWriteMode() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SettingsCatalogGenerator.generate(
                SettingsCatalogGenerator.declarations(Constants.class), Map.of(), null, null, true));

        assertTrue(e.getMessage().contains("-D" + SettingsCatalogGenerator.INTRODUCED_IN_PROPERTY), e.getMessage());
        assertTrue(e.getMessage().contains("fixtureMode"), e.getMessage());
        assertTrue(e.getMessage().contains("fixtureLastUpdate"), e.getMessage());
    }

    @Test
    void aNewKeyWithoutAVersionPassesTheVerifyMode() {
        SettingsCatalog catalog = SettingsCatalogGenerator.generate(
                SettingsCatalogGenerator.declarations(Constants.class), Map.of(), null, null, false);

        assertNull(catalog.byKey().get("fixtureMode").introducedIn(), "the diff then reports it as added");
    }

    @Test
    void everyProblemIsInTheOneMessage() {
        String message = assertBreaks("fixtureNoType", NoType.class, WebPassword.class, BadJson.class);
        assertTrue(message.contains("fixturePassword") && message.contains("fixtureBadJson"), message);
    }

    // ---------------------------------------------------------------- helpers

    private static SettingsCatalog generate(String introducedIn, Class<?>... types) {
        return SettingsCatalogGenerator.generate(declarations(types), Map.of(), null,
                introducedIn != null ? introducedIn : "4.0.1", false);
    }

    private static List<SettingsCatalogGenerator.Declaration> declarations(Class<?>... types) {
        List<SettingsCatalogGenerator.Declaration> declarations = new ArrayList<>();
        for (Class<?> type : types) {
            declarations.addAll(SettingsCatalogGenerator.declarations(type));
        }
        return declarations;
    }

    private static String assertBreaks(String expected, Class<?>... types) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> generate(null, types));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
        return e.getMessage();
    }
}
