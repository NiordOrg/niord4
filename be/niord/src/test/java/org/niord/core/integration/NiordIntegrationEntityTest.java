/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.integration;

import org.junit.jupiter.api.Test;
import org.niord.core.integration.vo.MessageSeriesMappingVo;
import org.niord.core.integration.vo.NiordIntegrationVo;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the integration entities {@link NiordIntegration}, {@link MessageSeriesMapping}
 * and the value object {@link MessageSeriesMappingVo}.
 *
 * <p>These are pure POJO/entity-conversion tests with no database or CDI involvement. They pin the
 * observable contract of the value-object {@code mappingDefined()} filter, the entity/Vo round-trip
 * conversions and the back-reference wiring done by {@code addMessageSeriesMapping}.
 */
public class NiordIntegrationEntityTest {

    /* ------------------------------------------------------------------ */
    /* MessageSeriesMappingVo.mappingDefined()                            */
    /* ------------------------------------------------------------------ */

    @Test
    public void mappingDefined_trueWhenBothSourceAndTargetNonBlank() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId("src-" + System.nanoTime());
        vo.setTargetSeriesId("tgt-" + System.nanoTime());
        assertTrue(vo.mappingDefined());
    }

    @Test
    public void mappingDefined_falseWhenSourceNull() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId(null);
        vo.setTargetSeriesId("tgt-" + System.nanoTime());
        assertFalse(vo.mappingDefined());
    }

    @Test
    public void mappingDefined_falseWhenSourceBlank() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId("   ");
        vo.setTargetSeriesId("tgt-" + System.nanoTime());
        assertFalse(vo.mappingDefined());
    }

    @Test
    public void mappingDefined_falseWhenTargetNull() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId("src-" + System.nanoTime());
        vo.setTargetSeriesId(null);
        assertFalse(vo.mappingDefined());
    }

    @Test
    public void mappingDefined_falseWhenTargetBlank() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId("src-" + System.nanoTime());
        vo.setTargetSeriesId("");
        assertFalse(vo.mappingDefined());
    }

    @Test
    public void mappingDefined_falseWhenBothNull() {
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        assertNull(vo.getSourceSeriesId());
        assertNull(vo.getTargetSeriesId());
        assertFalse(vo.mappingDefined());
    }

    /* ------------------------------------------------------------------ */
    /* NiordIntegration default constructor                               */
    /* ------------------------------------------------------------------ */

    @Test
    public void defaultConstructor_isActiveTrueAndNoMappings() {
        NiordIntegration integration = new NiordIntegration();
        assertTrue(integration.isActive());
        assertTrue(integration.getMessageSeriesMappings().isEmpty());
    }

    /* ------------------------------------------------------------------ */
    /* NiordIntegration(NiordIntegrationVo)                               */
    /* ------------------------------------------------------------------ */

    @Test
    public void voConstructor_copiesScalarFields() {
        long n = System.nanoTime();
        Date scheduled = new Date(1_700_000_000_000L + n % 1000);

        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setId(42);
        vo.setUrl("https://niord-" + n + ".example.org");
        vo.setActive(false);
        vo.setAssignNewUids(true);
        vo.setCreateBaseData(true);
        vo.setNextScheduledExecution(scheduled);

        NiordIntegration integration = new NiordIntegration(vo);

        assertEquals(Integer.valueOf(42), integration.getId());
        assertEquals("https://niord-" + n + ".example.org", integration.getUrl());
        assertFalse(integration.isActive());
        assertTrue(integration.isAssignNewUids());
        assertTrue(integration.isCreateBaseData());
        assertEquals(scheduled, integration.getNextScheduledExecution());
    }

    @Test
    public void voConstructor_filtersUndefinedMappingsAndSetsBackReference() {
        long n = System.nanoTime();

        // Fully defined mapping -> should be copied
        MessageSeriesMappingVo defined = new MessageSeriesMappingVo();
        defined.setSourceSeriesId("source-" + n);
        defined.setTargetSeriesId("target-" + n);

        // Mapping with a blank target -> should be filtered out by mappingDefined()
        MessageSeriesMappingVo undefined = new MessageSeriesMappingVo();
        undefined.setSourceSeriesId("source-blank-" + n);
        undefined.setTargetSeriesId("   ");

        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setUrl("https://host-" + n);
        vo.setMessageSeriesMappings(Arrays.asList(defined, undefined));

        NiordIntegration integration = new NiordIntegration(vo);

        List<MessageSeriesMapping> mappings = integration.getMessageSeriesMappings();
        assertEquals(1, mappings.size());

        MessageSeriesMapping copied = mappings.get(0);
        assertEquals("source-" + n, copied.getSourceSeriesId());
        assertEquals("target-" + n, copied.getTargetSeriesId());

        // addMessageSeriesMapping wires the back-reference to the owning integration
        assertSame(integration, copied.getNiordIntegration());
    }

    /* ------------------------------------------------------------------ */
    /* NiordIntegration.toVo()                                            */
    /* ------------------------------------------------------------------ */

    @Test
    public void toVo_roundTripsScalarFieldsAndMappings() {
        long n = System.nanoTime();
        Date scheduled = new Date(1_650_000_000_000L + n % 1000);

        NiordIntegration integration = new NiordIntegration();
        integration.setId(7);
        integration.setUrl("https://round-trip-" + n);
        integration.setActive(true);
        integration.setAssignNewUids(true);
        integration.setCreateBaseData(false);
        integration.setNextScheduledExecution(scheduled);

        MessageSeriesMapping m1 = new MessageSeriesMapping();
        m1.setSourceSeriesId("s1-" + n);
        m1.setTargetSeriesId("t1-" + n);
        integration.addMessageSeriesMapping(m1);

        MessageSeriesMapping m2 = new MessageSeriesMapping();
        m2.setSourceSeriesId("s2-" + n);
        m2.setTargetSeriesId("t2-" + n);
        integration.addMessageSeriesMapping(m2);

        NiordIntegrationVo vo = integration.toVo();

        assertEquals(Integer.valueOf(7), vo.getId());
        assertEquals("https://round-trip-" + n, vo.getUrl());
        assertTrue(vo.isActive());
        assertTrue(vo.isAssignNewUids());
        assertFalse(vo.isCreateBaseData());
        assertEquals(scheduled, vo.getNextScheduledExecution());

        List<MessageSeriesMappingVo> mappingVos = vo.getMessageSeriesMappings();
        assertEquals(2, mappingVos.size());
        assertEquals("s1-" + n, mappingVos.get(0).getSourceSeriesId());
        assertEquals("t1-" + n, mappingVos.get(0).getTargetSeriesId());
        assertEquals("s2-" + n, mappingVos.get(1).getSourceSeriesId());
        assertEquals("t2-" + n, mappingVos.get(1).getTargetSeriesId());
    }

    /* ------------------------------------------------------------------ */
    /* NiordIntegration.addMessageSeriesMapping()                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void addMessageSeriesMapping_appendsToListAndSetsBackReference() {
        NiordIntegration integration = new NiordIntegration();
        MessageSeriesMapping mapping = new MessageSeriesMapping();
        mapping.setSourceSeriesId("s-" + System.nanoTime());
        mapping.setTargetSeriesId("t-" + System.nanoTime());

        // back-reference is unset before being added
        assertNull(mapping.getNiordIntegration());

        integration.addMessageSeriesMapping(mapping);

        assertEquals(1, integration.getMessageSeriesMappings().size());
        assertSame(mapping, integration.getMessageSeriesMappings().get(0));
        assertSame(integration, mapping.getNiordIntegration());
    }

    /* ------------------------------------------------------------------ */
    /* MessageSeriesMapping(MessageSeriesMappingVo) and toVo()            */
    /* ------------------------------------------------------------------ */

    @Test
    public void mappingVoConstructor_copiesSourceTargetButLeavesIntegrationNull() {
        long n = System.nanoTime();
        MessageSeriesMappingVo vo = new MessageSeriesMappingVo();
        vo.setSourceSeriesId("source-" + n);
        vo.setTargetSeriesId("target-" + n);

        MessageSeriesMapping mapping = new MessageSeriesMapping(vo);

        assertEquals("source-" + n, mapping.getSourceSeriesId());
        assertEquals("target-" + n, mapping.getTargetSeriesId());
        // The constructor does not wire the owning integration; that only happens via add().
        assertNull(mapping.getNiordIntegration());
    }

    @Test
    public void mappingToVo_copiesSourceAndTarget() {
        long n = System.nanoTime();
        MessageSeriesMapping mapping = new MessageSeriesMapping();
        mapping.setSourceSeriesId("src-" + n);
        mapping.setTargetSeriesId("tgt-" + n);

        MessageSeriesMappingVo vo = mapping.toVo();

        assertEquals("src-" + n, vo.getSourceSeriesId());
        assertEquals("tgt-" + n, vo.getTargetSeriesId());
    }
}
