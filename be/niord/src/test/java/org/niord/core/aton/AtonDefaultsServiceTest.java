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
package org.niord.core.aton;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AtonDefaultsService} — the INT-1-preset / OSM node-type defaults engine.
 *
 * <p>The production bean loads its data asynchronously (a {@code StartupEvent} observer schedules a
 * 3-second {@link java.util.Timer}). To test the loading + all query logic deterministically and
 * WITHOUT Quarkus, this test instantiates the service directly and drives the private
 * {@code generateDefaults()} synchronously, after reflectively populating the {@code @ConfigProperty}
 * fields (defaultsParsing / int1Preset / ialaSkipSystem / log). The XSLT and preset resources live on
 * the niord module classpath at {@code aton/aton-osm-defaults.xslt} and {@code aton/INT-1-preset.xml}.
 *
 * <p>This is framework-neutral business logic — see docs/framework-coupled-tests.md for the note on the
 * reflection used to bootstrap the bean's config fields.
 */
public class AtonDefaultsServiceTest {

    private AtonDefaultsService service;

    @BeforeEach
    public void loadDefaults() throws Exception {
        service = new AtonDefaultsService();
        setField(service, "log", LoggerFactory.getLogger("AtonDefaultsServiceTest"));
        setField(service, "defaultsParsing", "aton/aton-osm-defaults.xslt");
        setField(service, "int1Preset", "aton/INT-1-preset.xml");
        setField(service, "ialaSkipSystem", IalaBuoyageSystem.IALA_A);

        Method generate = AtonDefaultsService.class.getDeclaredMethod("generateDefaults");
        generate.setAccessible(true);
        generate.invoke(service);
    }

    /* ---------------------------------------------------------------- */
    /* getNodeTypeNames                                                 */
    /* ---------------------------------------------------------------- */

    @Test
    public void loadsNodeTypesFromPreset() {
        List<String> all = service.getNodeTypeNames(null);
        assertFalse(all.isEmpty(), "the INT-1 preset must yield node types");
        // A substantial set of node types is defined in the preset.
        assertTrue(all.size() > 50, "expected many node types, got " + all.size());
    }

    @Test
    public void nodeTypeNamesAreSortedAndDistinct() {
        List<String> all = service.getNodeTypeNames(null);
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String::compareTo);
        assertEquals(sorted, all, "getNodeTypeNames(null) must be sorted ascending");
        assertEquals(all.size(),
                all.stream().distinct().count(), "getNodeTypeNames(null) must be distinct");
    }

    @Test
    public void nodeTypeNameFilterIsCaseInsensitiveSubstring() {
        List<String> buoy = service.getNodeTypeNames("buoy");
        assertFalse(buoy.isEmpty(), "there are buoy node types in the preset");
        assertTrue(buoy.stream().allMatch(n -> n.toLowerCase().contains("buoy")),
                "every match must contain the query substring (case-insensitive)");

        // Upper-case query yields the same matches (containsIgnoreCase).
        assertEquals(buoy, service.getNodeTypeNames("BUOY"));
    }

    @Test
    public void nodeTypeNameFilterWithNoMatchIsEmpty() {
        assertTrue(service.getNodeTypeNames("zzz-not-a-real-node-type-qqq").isEmpty());
    }

    @Test
    public void northCardinalBuoyIsAKnownNodeType() {
        assertTrue(service.getNodeTypeNames("North Cardinal Buoy")
                        .contains("North Cardinal Buoy"),
                "the preset is expected to define a North Cardinal Buoy node type");
    }

    @Test
    public void getNodeTypeNamesBeforeLoadReturnsEmptyList() {
        // WEB3: the defaults load asynchronously ~3s after start-up, so a request in the pre-load window
        // (or after a failed load) finds osmDefaults == null. getNodeTypeNames must degrade to an empty
        // list rather than throwing an NPE (which surfaced as an HTTP 500 on /rest/atons/defaults/node-types).
        AtonDefaultsService fresh = new AtonDefaultsService();   // generateDefaults() NOT invoked -> osmDefaults is null
        assertTrue(fresh.getNodeTypeNames(null).isEmpty(),
                "getNodeTypeNames(null) must be an empty list before the defaults are loaded, not throw");
        assertTrue(fresh.getNodeTypeNames("buoy").isEmpty(),
                "getNodeTypeNames(filter) must be an empty list before the defaults are loaded, not throw");
    }

    @Test
    public void autoCompleteBeforeLoadReturnsEmptyList() {
        // WEB3b: the tag-key / tag-value auto-complete paths (computeKeysForAton / getValuesForAtonAndKey,
        // both routing through the private computeMatchingNodeTypes and an all-keys fallback) dereference
        // osmDefaults too. Like the getNodeTypeNames sibling above (WEB3), a request in the async pre-load
        // window (or after a failed load) must degrade to an empty list rather than throwing an NPE
        // (HTTP 500 on /rest/atons/defaults/keys and /values).
        AtonDefaultsService fresh = new AtonDefaultsService();   // generateDefaults() NOT invoked -> osmDefaults is null
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");         // non-blank key so the osmDefaults deref is reached
        assertTrue(fresh.computeKeysForAton(aton, "seamark:buoy_cardinal", 20).isEmpty(),
                "computeKeysForAton must be an empty list before the defaults are loaded, not throw");
        assertTrue(fresh.getValuesForAtonAndKey(aton, "seamark:buoy_cardinal:category", "nor", 20).isEmpty(),
                "getValuesForAtonAndKey must be an empty list before the defaults are loaded, not throw");
    }

    /* ---------------------------------------------------------------- */
    /* mergeAtonWithNodeTypes                                           */
    /* ---------------------------------------------------------------- */

    @Test
    public void mergeAddsNodeTypeTagsToEmptyAton() {
        AtonNode aton = new AtonNode();
        service.mergeAtonWithNodeTypes(aton, "North Cardinal Buoy");

        assertFalse(aton.getTags().isEmpty(), "merging a node type must add tags");
        // The node type defines its object class -> a seamark:type tag is added.
        assertNotNull(aton.getTagValue("seamark:type"),
                "a buoy node type must contribute a seamark:type");
        assertTrue(aton.getTags().stream().allMatch(t -> t.getK().startsWith("seamark:")),
                "preset keys are seamark:* keys");
    }

    @Test
    public void mergeIsIdempotentAndDoesNotDuplicateKeys() {
        AtonNode aton = new AtonNode();
        service.mergeAtonWithNodeTypes(aton, "North Cardinal Buoy");
        int afterFirst = aton.getTags().size();

        service.mergeAtonWithNodeTypes(aton, "North Cardinal Buoy");
        assertEquals(afterFirst, aton.getTags().size(),
                "re-merging the same node type must not add duplicate keys");
    }

    @Test
    public void mergeDoesNotOverwriteExistingTagValues() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "CUSTOM-PRESET-VALUE");
        service.mergeAtonWithNodeTypes(aton, "North Cardinal Buoy");

        assertEquals("CUSTOM-PRESET-VALUE", aton.getTagValue("seamark:type"),
                "merge must only fill ABSENT keys, never overwrite");
    }

    @Test
    public void mergeWithUnknownNodeTypeLeavesAtonUnchanged() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");
        int before = aton.getTags().size();

        service.mergeAtonWithNodeTypes(aton, "No Such Node Type 12345");
        assertEquals(before, aton.getTags().size());
    }

    @Test
    public void mergeWithBlankNodeTypeIsNoOp() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");
        service.mergeAtonWithNodeTypes(aton, "   ");
        assertEquals(1, aton.getTags().size());
    }

    @Test
    public void mergeWithNullAtonDoesNotThrow() {
        // Null-aton guard: must return quietly.
        service.mergeAtonWithNodeTypes(null, "North Cardinal Buoy");
    }

    /* ---------------------------------------------------------------- */
    /* computeKeysForAton (auto-complete keys)                          */
    /* ---------------------------------------------------------------- */

    @Test
    public void computeKeysReturnsMatchingKeysNotAlreadyOnAton() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");

        List<String> keys = service.computeKeysForAton(aton, "seamark:buoy_cardinal", 20);
        assertFalse(keys.isEmpty(), "a cardinal buoy must offer seamark:buoy_cardinal:* keys");
        assertTrue(keys.stream().allMatch(k -> k.toLowerCase().contains("seamark:buoy_cardinal")),
                "every suggested key must contain the typed substring");
        assertTrue(keys.stream().noneMatch(k -> k.equals("seamark:type")),
                "suggestions must exclude keys already present on the AtoN");
        assertTrue(keys.size() <= 20, "must honour the max-key limit");
    }

    @Test
    public void computeKeysWithBlankKeyReturnsEmpty() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");
        assertTrue(service.computeKeysForAton(aton, "", 20).isEmpty());
        assertTrue(service.computeKeysForAton(aton, "   ", 20).isEmpty());
    }

    /* ---------------------------------------------------------------- */
    /* getValuesForAtonAndKey (auto-complete values)                    */
    /* ---------------------------------------------------------------- */

    @Test
    public void valuesForKeyAreFilteredByValueSubstring() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");

        List<String> values = service.getValuesForAtonAndKey(aton, "seamark:buoy_cardinal:category", "nor", 20);
        assertFalse(values.isEmpty(), "category values for a cardinal buoy must exist");
        assertTrue(values.stream().allMatch(v -> v.toLowerCase().contains("nor")),
                "every value must contain the typed value substring (case-insensitive)");
        // The north cardinal category is the obvious "nor" match.
        assertTrue(values.contains("north"), "expected 'north' among the matches");
    }

    @Test
    public void valuesWithBlankKeyReturnsEmpty() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");
        assertTrue(service.getValuesForAtonAndKey(aton, "", "x", 20).isEmpty());
    }

    @Test
    public void valuesForKeyWithoutValueFilterReturnsAll() {
        AtonNode aton = new AtonNode();
        aton.updateTag("seamark:type", "buoy_cardinal");

        List<String> values = service.getValuesForAtonAndKey(aton, "seamark:buoy_cardinal:category", null, 20);
        // The four cardinal categories should all be offered.
        assertTrue(values.contains("north"));
        assertTrue(values.contains("south"));
        assertTrue(values.contains("east"));
        assertTrue(values.contains("west"));
    }

    /* ---------------------------------------------------------------- */

    private static void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }
}
