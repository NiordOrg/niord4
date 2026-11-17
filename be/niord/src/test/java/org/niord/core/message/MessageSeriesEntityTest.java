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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.DataFilter;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.Type;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MessageSeries} entity's pure (non-persistent) logic:
 * <ul>
 *     <li>{@link MessageSeries#validTypes()} — falls back to the {@link MainType}'s implied sub-types
 *         when no explicit types are configured, otherwise returns the explicit set.</li>
 *     <li>{@link MessageSeries#validType(Type)} — membership against {@code validTypes()}.</li>
 *     <li>The plain {@link MessageSeriesVo} constructor (copies only seriesId + mainType).</li>
 *     <li>The {@link SystemMessageSeriesVo} constructor (copies the system-specific fields and
 *         defaults a null {@code numberSequenceType} to {@link NumberSequenceType#YEARLY}).</li>
 *     <li>{@link MessageSeries#toVo(Class, DataFilter)} and the one-arg overload — field-filtered
 *         value-object conversion, including the "details" gate and the non-empty-collection guards.</li>
 * </ul>
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Entities and
 * value objects are built directly with {@code new} + setters and asserted on concrete field values,
 * ordering and collection contents, so the tests survive a future Quarkus-&gt;Spring port.
 */
public class MessageSeriesEntityTest {

    // ------------------------------------------------------------------
    // validTypes()
    // ------------------------------------------------------------------

    @Test
    public void validTypesFallsBackToMainTypeNwSubTypesWhenTypesEmpty() {
        MessageSeries series = new MessageSeries("nw-series");
        series.setMainType(MainType.NW);
        // types defaults to an empty set

        Set<Type> expected = new HashSet<>(Arrays.asList(
                Type.COASTAL_WARNING,
                Type.SUBAREA_WARNING,
                Type.NAVAREA_WARNING,
                Type.LOCAL_WARNING));

        assertEquals(expected, series.validTypes());
    }

    @Test
    public void validTypesFallsBackToMainTypeNmSubTypesWhenTypesEmpty() {
        MessageSeries series = new MessageSeries("nm-series");
        series.setMainType(MainType.NM);

        Set<Type> expected = new HashSet<>(Arrays.asList(
                Type.PERMANENT_NOTICE,
                Type.TEMPORARY_NOTICE,
                Type.PRELIMINARY_NOTICE,
                Type.MISCELLANEOUS_NOTICE));

        assertEquals(expected, series.validTypes());
    }

    @Test
    public void validTypesThrowsNpeWhenMainTypeIsNullAndTypesEmpty() {
        // MSG1 root cause: validTypes() does `types.isEmpty() ? mainType.getTypes() : types`.
        // With an empty type set and an unset mainType, the empty-set branch dereferences a
        // null mainType. Pin the ACTUAL current behaviour: a NullPointerException is thrown.
        MessageSeries series = new MessageSeries("no-main-type-series");
        // mainType deliberately left null; types defaults to an empty set.
        assertTrue(series.getTypes().isEmpty());
        assertNull(series.getMainType());

        assertThrows(NullPointerException.class, series::validTypes);
    }

    @Test
    public void validTypeThrowsNpeWhenMainTypeIsNullAndTypesEmpty() {
        // validType(Type) delegates to validTypes(), so the same null mainType blows up here too.
        MessageSeries series = new MessageSeries("no-main-type-series");

        assertThrows(NullPointerException.class, () -> series.validType(Type.LOCAL_WARNING));
    }

    @Test
    public void validTypesReturnsExplicitSetWhenMainTypeNullButTypesNonEmpty() {
        // The non-empty-types branch never touches mainType, so an unset mainType is harmless.
        MessageSeries series = new MessageSeries("no-main-type-series");
        // mainType left null on purpose.
        series.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING, Type.COASTAL_WARNING)));

        Set<Type> result = series.validTypes();

        assertEquals(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING, Type.COASTAL_WARNING)), result);
        // And the membership helper works without dereferencing the null mainType.
        assertTrue(series.validType(Type.LOCAL_WARNING));
        assertFalse(series.validType(Type.PERMANENT_NOTICE));
    }

    @Test
    public void validTypesReturnsExplicitSetWhenTypesNonEmpty() {
        MessageSeries series = new MessageSeries("nw-series");
        series.setMainType(MainType.NW);
        series.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)));

        Set<Type> result = series.validTypes();

        assertEquals(1, result.size());
        assertTrue(result.contains(Type.LOCAL_WARNING));
        assertFalse(result.contains(Type.COASTAL_WARNING));
    }

    // ------------------------------------------------------------------
    // validType(Type)
    // ------------------------------------------------------------------

    @Test
    public void validTypeAcceptsImpliedSubTypeForEmptyTypeSet() {
        MessageSeries series = new MessageSeries("nw-series");
        series.setMainType(MainType.NW);

        assertTrue(series.validType(Type.LOCAL_WARNING));
    }

    @Test
    public void validTypeRejectsForeignMainTypeForEmptyTypeSet() {
        MessageSeries series = new MessageSeries("nw-series");
        series.setMainType(MainType.NW);

        // PERMANENT_NOTICE is an NM type, not implied by NW
        assertFalse(series.validType(Type.PERMANENT_NOTICE));
    }

    @Test
    public void validTypeRespectsExplicitRestrictionEvenWithinSameMainType() {
        MessageSeries series = new MessageSeries("nw-series");
        series.setMainType(MainType.NW);
        series.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)));

        // Both are NW types, but only LOCAL_WARNING is explicitly allowed
        assertTrue(series.validType(Type.LOCAL_WARNING));
        assertFalse(series.validType(Type.COASTAL_WARNING));
    }

    // ------------------------------------------------------------------
    // MessageSeries(MessageSeriesVo) - plain VO constructor
    // ------------------------------------------------------------------

    @Test
    public void plainVoConstructorCopiesSeriesIdAndMainTypeOnly() {
        MessageSeriesVo vo = new MessageSeriesVo();
        vo.setSeriesId("plain-series");
        vo.setMainType(MainType.NM);

        MessageSeries series = new MessageSeries(vo);

        assertEquals("plain-series", series.getSeriesId());
        assertEquals(MainType.NM, series.getMainType());
        // System-specific fields are untouched / default
        assertTrue(series.getTypes().isEmpty());
        assertNull(series.getShortFormat());
        assertNull(series.getNavtexFormat());
        assertEquals(NumberSequenceType.YEARLY, series.getNumberSequenceType());
        assertTrue(series.getEditorFields().isEmpty());
        assertNull(series.getExcludeFromMessageIndex());
    }

    // ------------------------------------------------------------------
    // MessageSeries(SystemMessageSeriesVo) - system VO constructor
    // ------------------------------------------------------------------

    @Test
    public void systemVoConstructorCopiesAllSystemFields() {
        SystemMessageSeriesVo vo = new SystemMessageSeriesVo();
        vo.setSeriesId("sys-series");
        vo.setMainType(MainType.NW);
        vo.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)));
        vo.setShortFormat("NW-${number}");
        vo.setNavtexFormat("nf");
        vo.setNumberSequenceType(NumberSequenceType.CONTINUOUS);
        vo.setEditorFields(Arrays.asList("title"));
        vo.setExcludeFromMessageIndex(Boolean.TRUE);

        MessageSeries series = new MessageSeries(vo);

        assertEquals("sys-series", series.getSeriesId());
        assertEquals(MainType.NW, series.getMainType());
        assertEquals(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)), series.getTypes());
        assertEquals("NW-${number}", series.getShortFormat());
        assertEquals("nf", series.getNavtexFormat());
        assertEquals(NumberSequenceType.CONTINUOUS, series.getNumberSequenceType());
        assertEquals(Arrays.asList("title"), series.getEditorFields());
        assertEquals(Boolean.TRUE, series.getExcludeFromMessageIndex());
    }

    @Test
    public void systemVoConstructorDefaultsNullNumberSequenceTypeToYearly() {
        SystemMessageSeriesVo vo = new SystemMessageSeriesVo();
        vo.setSeriesId("sys-series");
        vo.setMainType(MainType.NW);
        vo.setNumberSequenceType(null);

        MessageSeries series = new MessageSeries(vo);

        assertEquals(NumberSequenceType.YEARLY, series.getNumberSequenceType());
    }

    @Test
    public void systemVoConstructorWithNullCollectionsLeavesEntityCollectionsEmpty() {
        SystemMessageSeriesVo vo = new SystemMessageSeriesVo();
        vo.setSeriesId("sys-series");
        vo.setMainType(MainType.NM);
        // types / editorFields left null on the VO

        MessageSeries series = new MessageSeries(vo);

        assertTrue(series.getTypes().isEmpty());
        assertTrue(series.getEditorFields().isEmpty());
    }

    // ------------------------------------------------------------------
    // toVo(Class, DataFilter)
    // ------------------------------------------------------------------

    @Test
    public void toVoWithoutDetailsCopiesOnlySeriesId() {
        MessageSeries series = buildFullSystemSeries();

        SystemMessageSeriesVo vo = series.toVo(SystemMessageSeriesVo.class, DataFilter.get());

        assertEquals("full-series", vo.getSeriesId());
        // Without DETAILS, nothing else is copied
        assertNull(vo.getMainType());
        assertNull(vo.getTypes());
        assertNull(vo.getShortFormat());
        assertNull(vo.getNavtexFormat());
        assertNull(vo.getNumberSequenceType());
        assertNull(vo.getEditorFields());
        assertNull(vo.getExcludeFromMessageIndex());
    }

    @Test
    public void toVoWithAllFieldsCopiesEverythingForSystemVo() {
        MessageSeries series = buildFullSystemSeries();

        SystemMessageSeriesVo vo = series.toVo(SystemMessageSeriesVo.class,
                DataFilter.get().fields(DataFilter.ALL));

        assertEquals("full-series", vo.getSeriesId());
        assertEquals(MainType.NW, vo.getMainType());
        assertEquals(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)), vo.getTypes());
        assertEquals("NW-${number}", vo.getShortFormat());
        assertEquals("nf", vo.getNavtexFormat());
        assertEquals(NumberSequenceType.MANUAL, vo.getNumberSequenceType());
        assertEquals(Arrays.asList("title"), vo.getEditorFields());
        assertEquals(Boolean.TRUE, vo.getExcludeFromMessageIndex());
    }

    @Test
    public void toVoWithAllFieldsLeavesEmptyCollectionsNullOnTheVo() {
        // Series with empty types and editorFields - toVo only copies them when non-empty
        MessageSeries series = new MessageSeries("empty-coll-series");
        series.setMainType(MainType.NW);
        series.setShortFormat("sf");
        series.setNumberSequenceType(NumberSequenceType.NONE);
        series.setExcludeFromMessageIndex(Boolean.FALSE);

        SystemMessageSeriesVo vo = series.toVo(SystemMessageSeriesVo.class,
                DataFilter.get().fields(DataFilter.ALL));

        assertEquals("empty-coll-series", vo.getSeriesId());
        assertEquals(MainType.NW, vo.getMainType());
        // Empty collections are NOT copied (stay null on the VO)
        assertNull(vo.getTypes());
        assertNull(vo.getEditorFields());
        // Scalars are copied even when the source is null/empty-aware
        assertEquals("sf", vo.getShortFormat());
        assertEquals(NumberSequenceType.NONE, vo.getNumberSequenceType());
        assertEquals(Boolean.FALSE, vo.getExcludeFromMessageIndex());
    }

    @Test
    public void toVoWithAllFieldsIntoPlainVoIgnoresSystemFields() {
        // When the target class is the plain MessageSeriesVo, the system block is skipped
        MessageSeries series = buildFullSystemSeries();

        MessageSeriesVo vo = series.toVo(MessageSeriesVo.class,
                DataFilter.get().fields(DataFilter.ALL));

        assertEquals(MessageSeriesVo.class, vo.getClass());
        assertEquals("full-series", vo.getSeriesId());
        assertEquals(MainType.NW, vo.getMainType());
    }

    // ------------------------------------------------------------------
    // toVo(Class) one-arg overload (implicitly uses ALL fields)
    // ------------------------------------------------------------------

    @Test
    public void toVoOneArgOverloadIncludesDetails() {
        MessageSeries series = buildFullSystemSeries();

        SystemMessageSeriesVo vo = series.toVo(SystemMessageSeriesVo.class);

        assertEquals("full-series", vo.getSeriesId());
        // The one-arg overload defaults to ALL fields, so details are included
        assertEquals(MainType.NW, vo.getMainType());
        assertEquals(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)), vo.getTypes());
        assertEquals("NW-${number}", vo.getShortFormat());
        assertEquals(NumberSequenceType.MANUAL, vo.getNumberSequenceType());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Builds a fully-populated NW system message series used by several toVo tests. */
    private static MessageSeries buildFullSystemSeries() {
        MessageSeries series = new MessageSeries("full-series");
        series.setMainType(MainType.NW);
        series.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)));
        series.setShortFormat("NW-${number}");
        series.setNavtexFormat("nf");
        series.setNumberSequenceType(NumberSequenceType.MANUAL);
        series.setEditorFields(List.of("title"));
        series.setExcludeFromMessageIndex(Boolean.TRUE);
        return series;
    }
}
