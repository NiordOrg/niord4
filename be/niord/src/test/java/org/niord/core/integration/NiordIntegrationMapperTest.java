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

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NiordIntegrationMapper}: pins the field projection that was previously
 * embedded in {@code NiordIntegration.toVo()} / its conversion constructor (and the owned
 * {@code MessageSeriesMapping} conversion), so the extraction is provably behaviour-preserving.
 * Runs as a plain unit test with no persistence context.
 */
public class NiordIntegrationMapperTest {

    private final NiordIntegrationMapper mapper = new NiordIntegrationMapper();

    private static MessageSeriesMapping mapping(String source, String target) {
        MessageSeriesMapping m = new MessageSeriesMapping();
        m.setSourceSeriesId(source);
        m.setTargetSeriesId(target);
        return m;
    }

    private static NiordIntegration integration() {
        NiordIntegration i = new NiordIntegration();
        i.setId(42);
        i.setUrl("https://other.niord.example/rest");
        i.setActive(false);
        i.setAssignNewUids(true);
        i.setCreateBaseData(true);
        i.setNextScheduledExecution(new Date(1_500_000_000_000L));
        i.addMessageSeriesMapping(mapping("dk-nm", "local-nm"));
        i.addMessageSeriesMapping(mapping("dk-nw", "local-nw"));
        return i;
    }

    @Test
    public void toVoCopiesAllScalarFields() {
        NiordIntegrationVo vo = mapper.toVo(integration());

        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals("https://other.niord.example/rest", vo.getUrl());
        assertFalse(vo.isActive());
        assertTrue(vo.isAssignNewUids());
        assertTrue(vo.isCreateBaseData());
        assertEquals(new Date(1_500_000_000_000L), vo.getNextScheduledExecution());
    }

    @Test
    public void toVoCopiesOwnedMappingChildren() {
        NiordIntegrationVo vo = mapper.toVo(integration());

        List<MessageSeriesMappingVo> mappings = vo.getMessageSeriesMappings();
        assertEquals(2, mappings.size());
        assertEquals("dk-nm", mappings.get(0).getSourceSeriesId());
        assertEquals("local-nm", mappings.get(0).getTargetSeriesId());
        assertEquals("dk-nw", mappings.get(1).getSourceSeriesId());
        assertEquals("local-nw", mappings.get(1).getTargetSeriesId());
    }

    @Test
    public void toVoOnEmptyMappingsYieldsEmptyList() {
        NiordIntegration i = new NiordIntegration();
        i.setUrl("https://empty.example/rest");

        NiordIntegrationVo vo = mapper.toVo(i);

        assertTrue(vo.getMessageSeriesMappings().isEmpty());
    }

    @Test
    public void nullEntityMapsToNullVo() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void toEntityCopiesAllScalarFields() {
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setId(7);
        vo.setUrl("https://import.example/rest");
        vo.setActive(true);
        vo.setAssignNewUids(false);
        vo.setCreateBaseData(false);
        vo.setNextScheduledExecution(new Date(1_600_000_000_000L));

        NiordIntegration entity = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(7), entity.getId());
        assertEquals("https://import.example/rest", entity.getUrl());
        assertTrue(entity.isActive());
        assertFalse(entity.isAssignNewUids());
        assertFalse(entity.isCreateBaseData());
        assertEquals(new Date(1_600_000_000_000L), entity.getNextScheduledExecution());
    }

    @Test
    public void toEntityKeepsFullyDefinedMappingsAndSetsBackReference() {
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setUrl("https://import.example/rest");
        MessageSeriesMappingVo m = new MessageSeriesMappingVo();
        m.setSourceSeriesId("dk-nm");
        m.setTargetSeriesId("local-nm");
        vo.getMessageSeriesMappings().add(m);

        NiordIntegration entity = mapper.toEntity(vo);

        assertEquals(1, entity.getMessageSeriesMappings().size());
        MessageSeriesMapping child = entity.getMessageSeriesMappings().get(0);
        assertEquals("dk-nm", child.getSourceSeriesId());
        assertEquals("local-nm", child.getTargetSeriesId());
        // addMessageSeriesMapping wires the owning back-reference
        assertSame(entity, child.getNiordIntegration());
    }

    @Test
    public void toEntityDropsMappingsThatAreNotFullyDefined() {
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setUrl("https://import.example/rest");

        MessageSeriesMappingVo defined = new MessageSeriesMappingVo();
        defined.setSourceSeriesId("dk-nm");
        defined.setTargetSeriesId("local-nm");

        MessageSeriesMappingVo missingTarget = new MessageSeriesMappingVo();
        missingTarget.setSourceSeriesId("dk-nw");

        MessageSeriesMappingVo blank = new MessageSeriesMappingVo();
        blank.setSourceSeriesId("  ");
        blank.setTargetSeriesId("");

        vo.getMessageSeriesMappings().add(defined);
        vo.getMessageSeriesMappings().add(missingTarget);
        vo.getMessageSeriesMappings().add(blank);

        NiordIntegration entity = mapper.toEntity(vo);

        // Only the fully-defined mapping survives the mappingDefined() filter
        assertEquals(1, entity.getMessageSeriesMappings().size());
        assertEquals("dk-nm", entity.getMessageSeriesMappings().get(0).getSourceSeriesId());
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    public void roundTripPreservesScalarFieldsAndDefinedMappings() {
        NiordIntegrationVo vo = mapper.toVo(integration());

        NiordIntegration entity = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(42), entity.getId());
        assertEquals("https://other.niord.example/rest", entity.getUrl());
        assertFalse(entity.isActive());
        assertTrue(entity.isAssignNewUids());
        assertTrue(entity.isCreateBaseData());
        assertEquals(new Date(1_500_000_000_000L), entity.getNextScheduledExecution());
        assertEquals(2, entity.getMessageSeriesMappings().size());
        assertEquals("dk-nm", entity.getMessageSeriesMappings().get(0).getSourceSeriesId());
        assertEquals("local-nw", entity.getMessageSeriesMappings().get(1).getTargetSeriesId());
    }
}
