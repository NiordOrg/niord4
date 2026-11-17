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
package org.niord.core.script;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.TemplateModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.LineStringVo;

import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Framework-neutral Tier-1 unit tests for {@link GeoJsonAdapter} and {@link NiordAppObjectWrapper}.
 *
 * <p>{@link NiordAppObjectWrapper} is a Freemarker {@code DefaultObjectWrapper} that special-cases
 * {@link FeatureCollectionVo}: such values are wrapped into a {@link GeoJsonAdapter} (a
 * {@code TemplateSequenceModel} backed by {@code GeoJsonUtils.serializeFeatureCollection}), while any
 * other value falls through to the default wrapping. These tests verify the wrapper's dispatch, the
 * adapter's {@code size}/{@code get}/{@code getAdaptedObject} sequence semantics (including out-of-range
 * and empty-collection behaviour), and an end-to-end render of a {@code <#list>} over the adapter.
 *
 * <p>Only Freemarker and plain VOs are involved, so the tests survive a later Quarkus-to-Spring migration.
 */
public class GeoJsonAdapterTest {

    /** Builds the NiordAppObjectWrapper used in production, off a 2.3.31 Configuration. */
    private static NiordAppObjectWrapper wrapper() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        return new NiordAppObjectWrapper(cfg.getIncompatibleImprovements());
    }

    /** Builds a one-feature collection: a named LineString feature ("feat"). */
    private static FeatureCollectionVo oneFeatureCollection() {
        FeatureVo f = new FeatureVo();
        f.setGeometry(new LineStringVo(new double[][]{{10, 56}, {11, 57}}));
        f.getProperties().put("name:en", "feat");
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{f});
        return fc;
    }

    /** Renders a template body through a Configuration wired with the NiordAppObjectWrapper. */
    private static String render(String body, Map<String, Object> data) throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("t", body);
        cfg.setTemplateLoader(loader);
        cfg.setObjectWrapper(new NiordAppObjectWrapper(cfg.getIncompatibleImprovements()));
        cfg.setLocale(Locale.ENGLISH);

        StringWriter out = new StringWriter();
        cfg.getTemplate("t").process(data, out);
        return out.toString();
    }

    @Test
    public void wrapFeatureCollectionReturnsGeoJsonAdapter() throws Exception {
        // wrap(FeatureCollectionVo) is special-cased into a GeoJsonAdapter sequence model.
        TemplateModel model = wrapper().wrap(oneFeatureCollection());
        Assertions.assertTrue(model instanceof GeoJsonAdapter);
    }

    @Test
    public void wrapStringDoesNotReturnGeoJsonAdapter() throws Exception {
        // Non-FeatureCollectionVo values fall through to the default wrapping, never a GeoJsonAdapter.
        TemplateModel model = wrapper().wrap("hello");
        Assertions.assertFalse(model instanceof GeoJsonAdapter);
    }

    @Test
    public void adapterSizeOfOneFeatureCollectionIsOne() throws Exception {
        // A single non-skipped feature yields exactly one serialized element.
        GeoJsonAdapter adapter = new GeoJsonAdapter(oneFeatureCollection(), wrapper());
        Assertions.assertEquals(1, adapter.size());
    }

    @Test
    public void adapterGetReturnsElementForValidIndexAndNullForOutOfRange() throws Exception {
        // get(0) returns the wrapped element; any out-of-range index (>= size, or negative) returns null.
        GeoJsonAdapter adapter = new GeoJsonAdapter(oneFeatureCollection(), wrapper());
        Assertions.assertTrue(adapter.get(0) != null, "get(0) must return the single element");
        Assertions.assertNull(adapter.get(5), "get(5) is beyond the end and must be null");
        Assertions.assertNull(adapter.get(-1), "get(-1) is negative and must be null");
    }

    @Test
    public void getAdaptedObjectReturnsSerializedFeatureList() throws Exception {
        // getAdaptedObject exposes the underlying serialized-feature List (size 1 here, one per feature).
        GeoJsonAdapter adapter = new GeoJsonAdapter(oneFeatureCollection(), wrapper());
        Object adapted = adapter.getAdaptedObject(null);
        Assertions.assertTrue(adapted instanceof List, "Adapted object must be a java.util.List");
        Assertions.assertEquals(1, ((List<?>) adapted).size());
    }

    @Test
    public void adapterOfEmptyCollectionHasSizeZeroAndNullElements() throws Exception {
        // A collection with an empty (but non-null) feature array serializes to an empty list:
        // size 0 and get(0) is null.
        FeatureCollectionVo emptyFc = new FeatureCollectionVo();
        emptyFc.setFeatures(new FeatureVo[0]);
        GeoJsonAdapter adapter = new GeoJsonAdapter(emptyFc, wrapper());
        Assertions.assertEquals(0, adapter.size());
        Assertions.assertNull(adapter.get(0));
    }

    @Test
    public void adapterOfDefaultCollectionWithNullFeaturesHasSizeZero() throws Exception {
        // Fixed (docs/potential-errors.md SCRIPT1): GeoJsonUtils.serializeFeatureCollection now guards
        // a non-null collection whose features array is null (the default state of a fresh
        // FeatureCollectionVo), treating it like an empty collection instead of NPEing on iteration.
        FeatureCollectionVo defaultFc = new FeatureCollectionVo();
        Assertions.assertNull(defaultFc.getFeatures(), "precondition: a fresh collection has a null features array");

        GeoJsonAdapter adapter = new GeoJsonAdapter(defaultFc, wrapper());

        Assertions.assertEquals(0, adapter.size());
        Assertions.assertNull(adapter.get(0));
    }

    @Test
    public void templateListOverFeatureCollectionRendersFeatureName() throws Exception {
        // <#list fc as feat>${feat.name}</#list> iterates the adapter and prints the serialized name.
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("fc", oneFeatureCollection());
        String output = render("<#list fc as feat>${feat.name}</#list>", data);
        Assertions.assertEquals("feat", output);
    }

    @Test
    public void templateListOverEmptyFeatureCollectionRendersNothing() throws Exception {
        // An empty collection yields a zero-length sequence, so the <#list> body never executes.
        FeatureCollectionVo emptyFc = new FeatureCollectionVo();
        emptyFc.setFeatures(new FeatureVo[0]);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("fc", emptyFc);
        String output = render("<#list fc as feat>${feat.name}</#list>", data);
        Assertions.assertEquals("", output);
    }
}
