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
package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainMapper;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.DataFilter;
import org.niord.model.message.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PromulgationTypeMapper}: the projection and the {@code Domain} edge that were
 * previously embedded in {@code PromulgationType.toVo(...)} / the {@code PromulgationType(PromulgationTypeVo)}
 * conversion constructor.
 *
 * <p>These are mostly DIFFERENTIAL tests: the entity's own {@code toVo(...)} / conversion constructor still
 * exist, so the mapper output is asserted to match the entity's output field-by-field (incl. the nested
 * {@code Domain} graph and a {@link DataFilter} details-on / details-off pair). The {@link DomainMapper}
 * collaborator is wired by hand; bare domains (no nested areas/categories/message-series) exercise the edge
 * without needing the domain sub-mappers.
 */
public class PromulgationTypeMapperTest {

    private final PromulgationTypeMapper mapper = new PromulgationTypeMapper(new DomainMapper());

    /** Builds a fully-populated entity, including a bare nested domain. */
    private static PromulgationType fullType() {
        PromulgationType t = new PromulgationType();
        t.setTypeId("NAVTEX-DK");
        t.setServiceId("navtex");
        t.setName("NAVTEX Denmark");
        t.setPriority(7);
        t.setActive(true);
        t.setRequirement(Requirement.MANDATORY);
        t.setLanguage("da");

        Domain d = new Domain();
        d.setDomainId("dma-nw");
        d.setActive(true);
        d.setName("NW");
        t.setDomains(new ArrayList<>(List.of(d)));

        t.setMessageTypes(new HashSet<>(Arrays.asList(Type.PRELIMINARY_NOTICE, Type.TEMPORARY_NOTICE)));
        t.setScriptResourcePaths(new ArrayList<>(Arrays.asList("a.ftl", "b.ftl")));
        return t;
    }

    /** Asserts two value objects carry the same field values across the nested graph. */
    private static void assertVoEquals(PromulgationTypeVo expected, PromulgationTypeVo actual) {
        assertEquals(expected.getTypeId(), actual.getTypeId());
        assertEquals(expected.getServiceId(), actual.getServiceId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getPriority(), actual.getPriority());
        assertEquals(expected.isActive(), actual.isActive());
        assertEquals(expected.getRequirement(), actual.getRequirement());
        assertEquals(expected.getLanguage(), actual.getLanguage());
        assertEquals(expected.getMessageTypes(), actual.getMessageTypes());
        assertEquals(expected.getScriptResourcePaths(), actual.getScriptResourcePaths());

        if (expected.getDomains() == null) {
            assertNull(actual.getDomains());
        } else {
            assertEquals(expected.getDomains().size(), actual.getDomains().size());
            for (int i = 0; i < expected.getDomains().size(); i++) {
                assertEquals(expected.getDomains().get(i).getDomainId(),
                        actual.getDomains().get(i).getDomainId());
                assertEquals(expected.getDomains().get(i).isActive(),
                        actual.getDomains().get(i).isActive());
                assertEquals(expected.getDomains().get(i).getName(),
                        actual.getDomains().get(i).getName());
            }
        }
    }

    // ----- toVo: differential against the entity's own toVo -----

    @Test
    public void detailsFilterMatchesEntityToVo() {
        PromulgationType t = fullType();
        DataFilter filter = DataFilter.get().fields(DataFilter.DETAILS);

        assertVoEquals(t.toVo(filter), mapper.toVo(t, filter));
    }

    @Test
    public void defaultToVoMatchesEntityToVo() {
        PromulgationType t = fullType();

        // The no-arg overload must reproduce the entity's DETAILS-projected default.
        assertVoEquals(t.toVo(), mapper.toVo(t));
    }

    @Test
    public void detailsOnPopulatesDetailFields() {
        PromulgationType t = fullType();

        PromulgationTypeVo vo = mapper.toVo(t, DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("NAVTEX-DK", vo.getTypeId());
        assertEquals("navtex", vo.getServiceId());
        assertEquals("NAVTEX Denmark", vo.getName());
        assertEquals(7, vo.getPriority());
        assertEquals(Requirement.MANDATORY, vo.getRequirement());
        assertEquals(Boolean.TRUE, vo.isActive());
        assertEquals("da", vo.getLanguage());
        assertEquals(1, vo.getDomains().size());
        assertEquals("dma-nw", vo.getDomains().get(0).getDomainId());
        assertEquals(new HashSet<>(Arrays.asList(Type.PRELIMINARY_NOTICE, Type.TEMPORARY_NOTICE)),
                vo.getMessageTypes());
        assertEquals(Arrays.asList("a.ftl", "b.ftl"), vo.getScriptResourcePaths());
    }

    @Test
    public void detailsOffOmitsDetailFieldsLikeEntity() {
        PromulgationType t = fullType();
        DataFilter filter = DataFilter.get(); // no DETAILS field

        PromulgationTypeVo entityVo = t.toVo(filter);
        PromulgationTypeVo mapperVo = mapper.toVo(t, filter);

        // Same projection on both sides.
        assertVoEquals(entityVo, mapperVo);

        // And concretely: the base fields are present, the detail fields are suppressed.
        assertEquals("NAVTEX-DK", mapperVo.getTypeId());
        assertEquals("navtex", mapperVo.getServiceId());
        assertEquals("NAVTEX Denmark", mapperVo.getName());
        assertEquals(7, mapperVo.getPriority());
        assertEquals(Requirement.MANDATORY, mapperVo.getRequirement());
        assertNull(mapperVo.isActive());
        assertNull(mapperVo.getLanguage());
        assertNull(mapperVo.getDomains());
        assertNull(mapperVo.getMessageTypes());
        assertNull(mapperVo.getScriptResourcePaths());
    }

    @Test
    public void emptyCollectionsAreLeftNullUnderDetails() {
        // Mirrors the entity guard: empty messageTypes / scriptResourcePaths are NOT copied.
        PromulgationType t = new PromulgationType();
        t.setTypeId("EMPTY");
        t.setServiceId("svc");
        t.setName("n");

        PromulgationTypeVo entityVo = t.toVo(DataFilter.get().fields(DataFilter.DETAILS));
        PromulgationTypeVo mapperVo = mapper.toVo(t, DataFilter.get().fields(DataFilter.DETAILS));

        assertVoEquals(entityVo, mapperVo);
        assertNull(mapperVo.getMessageTypes());
        assertNull(mapperVo.getScriptResourcePaths());
        // Empty domain list IS set (entity sets it unconditionally under details).
        assertTrue(mapperVo.getDomains().isEmpty());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
        assertNull(mapper.toVo(null, DataFilter.get()));
    }

    // ----- toEntity / updateEntity: differential against the entity's conversion constructor -----

    /** Builds a fully-populated value object. */
    private static PromulgationTypeVo fullVo() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId("SAFETYNET-GL");
        vo.setServiceId("safetynet");
        vo.setName("SafetyNET Greenland");
        vo.setPriority(3);
        vo.setActive(Boolean.TRUE);
        vo.setRequirement(Requirement.OPTIONAL);
        vo.setLanguage("kl");
        vo.setMessageTypes(new HashSet<>(Arrays.asList(Type.PRELIMINARY_NOTICE)));
        vo.setScriptResourcePaths(new ArrayList<>(Arrays.asList("x.ftl")));
        return vo;
    }

    /** Asserts two entities carry the same field values. */
    private static void assertEntityEquals(PromulgationType expected, PromulgationType actual) {
        assertEquals(expected.getTypeId(), actual.getTypeId());
        assertEquals(expected.getServiceId(), actual.getServiceId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getPriority(), actual.getPriority());
        assertEquals(expected.isActive(), actual.isActive());
        assertEquals(expected.getRequirement(), actual.getRequirement());
        assertEquals(expected.getLanguage(), actual.getLanguage());
        assertEquals(expected.getMessageTypes(), actual.getMessageTypes());
        assertEquals(expected.getScriptResourcePaths(), actual.getScriptResourcePaths());
        assertEquals(expected.getDomains().size(), actual.getDomains().size());
    }

    @Test
    public void toEntityMatchesEntityConversionConstructor() {
        PromulgationTypeVo vo = fullVo();

        PromulgationType viaConstructor = new PromulgationType(vo);
        PromulgationType viaMapper = mapper.toEntity(vo);

        assertEntityEquals(viaConstructor, viaMapper);
    }

    @Test
    public void toEntityPopulatesAllFields() {
        PromulgationType t = mapper.toEntity(fullVo());

        assertEquals("SAFETYNET-GL", t.getTypeId());
        assertEquals("safetynet", t.getServiceId());
        assertEquals("SafetyNET Greenland", t.getName());
        assertEquals(3, t.getPriority());
        assertTrue(t.isActive());
        assertEquals(Requirement.OPTIONAL, t.getRequirement());
        assertEquals("kl", t.getLanguage());
        assertEquals(new HashSet<>(Arrays.asList(Type.PRELIMINARY_NOTICE)), t.getMessageTypes());
        assertEquals(Arrays.asList("x.ftl"), t.getScriptResourcePaths());
    }

    @Test
    public void nullActiveBecomesFalseLikeConstructor() {
        // Mirrors the entity guard: active = isActive() != null && isActive().
        PromulgationTypeVo vo = fullVo();
        vo.setActive(null);

        PromulgationType viaConstructor = new PromulgationType(vo);
        PromulgationType viaMapper = mapper.toEntity(vo);

        assertFalse(viaConstructor.isActive());
        assertFalse(viaMapper.isActive());
    }

    @Test
    public void nullCollectionsAreLeftUntouched() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId("MIN");
        vo.setServiceId("svc");
        vo.setName("n");
        // domains / messageTypes / scriptResourcePaths all null

        PromulgationType t = mapper.toEntity(vo);

        // Entity-side defaults are preserved (the conversion logic skips null collections).
        assertTrue(t.getDomains().isEmpty());
        assertTrue(t.getMessageTypes().isEmpty());
        assertTrue(t.getScriptResourcePaths().isEmpty());
    }

    @Test
    public void updateEntityOverwritesScalarsAndAddsCollections() {
        PromulgationType t = new PromulgationType();
        t.setTypeId("OLD");
        t.setName("old-name");

        PromulgationTypeVo vo = fullVo();
        mapper.updateEntity(t, vo);

        assertEquals("SAFETYNET-GL", t.getTypeId());
        assertEquals("SafetyNET Greenland", t.getName());
        assertEquals(3, t.getPriority());
        assertEquals(Requirement.OPTIONAL, t.getRequirement());
        assertEquals(new HashSet<>(Arrays.asList(Type.PRELIMINARY_NOTICE)), t.getMessageTypes());
        assertEquals(Arrays.asList("x.ftl"), t.getScriptResourcePaths());
    }

    @Test
    public void nullVoMapsToNullEntity() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    public void messageTypesAreCopiedNotShared() {
        // The entity copies into a fresh HashSet under details; mutating the source must not leak.
        PromulgationType t = fullType();
        Set<Type> source = t.getMessageTypes();

        PromulgationTypeVo vo = mapper.toVo(t, DataFilter.get().fields(DataFilter.DETAILS));
        source.add(Type.MISCELLANEOUS_NOTICE);

        assertFalse(vo.getMessageTypes().contains(Type.MISCELLANEOUS_NOTICE));
    }
}
