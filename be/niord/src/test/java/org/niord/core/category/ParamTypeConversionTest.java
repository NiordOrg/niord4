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

package org.niord.core.category;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.niord.core.category.vo.CompositeParamTypeVo;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.core.category.vo.StandardParamTypeVo;
import org.niord.core.category.vo.TemplateParamDescVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.model.DataFilter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for the entity &lt;-&gt; VO conversions of the
 * {@code ParamType} hierarchy ({@link StandardParamType}, {@link ListParamType},
 * {@link CompositeParamType}) and the {@link TemplateParam} entity.
 *
 * <p>These conversions are pure in-memory object transforms: no database, no CDI,
 * no Quarkus. All VO conversions use {@link DataFilter}; {@link DataFilter#get()}
 * is passed wherever a filter is needed. The Jackson polymorphism tests use a plain
 * {@link ObjectMapper} that is already on the classpath.
 */
public class ParamTypeConversionTest {

    /* ===================================================================== */
    /* STANDARD                                                              */
    /* ===================================================================== */

    /** 1. StandardParamType(name).toVo() carries the name and a null id. */
    @Test
    public void standardParamTypeToVo_carriesNameAndNullId() {
        StandardParamTypeVo vo = new StandardParamType("text").toVo(DataFilter.get());

        assertEquals("text", vo.getName());
        assertNull(vo.getId(), "A transient (unpersisted) entity has no id");
    }

    /** 2. StandardParamType(StandardParamTypeVo) copies both the id and the name. */
    @Test
    public void standardParamTypeFromVo_copiesIdAndName() {
        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setId(5);
        vo.setName("x");

        StandardParamType entity = new StandardParamType(vo);

        assertEquals("x", entity.getName());
        assertEquals(Integer.valueOf(5), entity.getId());
    }

    /** 3. ParamTypeVo.toEntity() on a StandardParamTypeVo yields a StandardParamType with the same name. */
    @Test
    public void standardParamTypeVoToEntity_returnsStandardParamTypeWithSameName() {
        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setName("boolean");

        ParamType entity = vo.toEntity();

        assertTrue(entity instanceof StandardParamType,
                "toEntity() must dispatch to the standard entity type");
        assertEquals("boolean", entity.getName());
    }

    /* ===================================================================== */
    /* LIST                                                                  */
    /* ===================================================================== */

    /** 4. ListParamTypeVo.toEntity() builds a ListParamType with one DictionaryEntry per value, order preserved. */
    @Test
    public void listParamTypeVoToEntity_buildsDictionaryEntriesInOrder() {
        DictionaryEntryVo a = new DictionaryEntryVo();
        a.setId(11);
        a.setKey("a");
        DictionaryEntryVo b = new DictionaryEntryVo();
        b.setId(22);
        b.setKey("b");

        ListParamTypeVo vo = new ListParamTypeVo();
        vo.setId(7);
        vo.setName("colors");
        vo.setValues(new ArrayList<>(List.of(a, b)));

        ParamType paramType = vo.toEntity();
        assertTrue(paramType instanceof ListParamType);
        ListParamType entity = (ListParamType) paramType;

        // id + name carried through (ParamType super-constructor)
        assertEquals(Integer.valueOf(7), entity.getId());
        assertEquals("colors", entity.getName());

        // values built via new DictionaryEntry(v) per value, order preserved
        List<DictionaryEntry> values = entity.getValues();
        assertEquals(2, values.size());
        assertEquals("a", values.get(0).getKey());
        assertEquals(Integer.valueOf(11), values.get(0).getId());
        assertEquals("b", values.get(1).getKey());
        assertEquals(Integer.valueOf(22), values.get(1).getId());
    }

    /** 5. ListParamType.toVo() emits one DictionaryEntryVo per value with the same keys. */
    @Test
    public void listParamTypeToVo_emitsDictionaryEntryVosWithSameKeys() {
        DictionaryEntry e1 = new DictionaryEntry();
        e1.setKey("first");
        DictionaryEntry e2 = new DictionaryEntry();
        e2.setKey("second");

        ListParamType entity = new ListParamType();
        entity.setName("L");
        entity.setValues(new ArrayList<>(List.of(e1, e2)));

        ListParamTypeVo vo = entity.toVo(DataFilter.get());

        assertEquals("L", vo.getName());
        List<DictionaryEntryVo> values = vo.getValues();
        assertEquals(2, values.size());
        assertEquals("first", values.get(0).getKey());
        assertEquals("second", values.get(1).getKey());
    }

    /** 6. ParamTypeVo.toEntity() polymorphism: a ListParamTypeVo yields ListParamType (not the others). */
    @Test
    public void listParamTypeVoToEntity_isListParamTypeAndNotOthers() {
        ParamType entity = new ListParamTypeVo().toEntity();

        assertTrue(entity instanceof ListParamType);
        assertFalse(entity instanceof StandardParamType);
        assertFalse(entity instanceof CompositeParamType);
    }

    /* ===================================================================== */
    /* COMPOSITE                                                             */
    /* ===================================================================== */

    /** 7. CompositeParamTypeVo.toEntity() builds a CompositeParamType with one TemplateParam carrying the fields. */
    @Test
    public void compositeParamTypeVoToEntity_buildsTemplateParam() {
        TemplateParamVo paramVo = new TemplateParamVo();
        paramVo.setParamId("p1");
        paramVo.setType("text");
        paramVo.setMandatory(true);
        paramVo.setPositionList(false);

        CompositeParamTypeVo vo = new CompositeParamTypeVo();
        vo.setName("C");
        vo.setTemplateParams(new ArrayList<>(List.of(paramVo)));

        ParamType paramType = vo.toEntity();
        assertTrue(paramType instanceof CompositeParamType);
        CompositeParamType entity = (CompositeParamType) paramType;

        assertEquals("C", entity.getName());
        List<TemplateParam> params = entity.getTemplateParams();
        assertEquals(1, params.size());
        TemplateParam p = params.get(0);
        assertEquals("p1", p.getParamId());
        assertEquals("text", p.getType());
        assertTrue(p.isMandatory());
        assertFalse(p.isPositionList());
    }

    /** 8. CompositeParamType.toVo() round-trips the template-param fields. */
    @Test
    public void compositeParamTypeToVo_roundTripsTemplateParamFields() {
        TemplateParam param = new TemplateParam();
        param.setParamId("q9");
        param.setType("number");
        param.setMandatory(false);
        param.setPositionList(true);

        CompositeParamType entity = new CompositeParamType();
        entity.setName("Composite");
        entity.setTemplateParams(new ArrayList<>(List.of(param)));

        CompositeParamTypeVo vo = entity.toVo(DataFilter.get());

        assertEquals("Composite", vo.getName());
        List<TemplateParamVo> params = vo.getTemplateParams();
        assertEquals(1, params.size());
        TemplateParamVo p = params.get(0);
        assertEquals("q9", p.getParamId());
        assertEquals("number", p.getType());
        assertFalse(p.isMandatory());
        assertTrue(p.isPositionList());
    }

    /** 9. ParamTypeVo.toEntity() polymorphism: a CompositeParamTypeVo yields CompositeParamType. */
    @Test
    public void compositeParamTypeVoToEntity_isCompositeParamType() {
        ParamType entity = new CompositeParamTypeVo().toEntity();

        assertTrue(entity instanceof CompositeParamType);
        assertFalse(entity instanceof StandardParamType);
        assertFalse(entity instanceof ListParamType);
    }

    /* ===================================================================== */
    /* TEMPLATEPARAM                                                         */
    /* ===================================================================== */

    /** 10. TemplateParam(TemplateParamVo) copies the scalar fields plus the localized descriptions. */
    @Test
    public void templateParamFromVo_copiesFieldsAndDescs() {
        TemplateParamVo vo = new TemplateParamVo();
        vo.setParamId("pos");
        vo.setType("text");
        vo.setMandatory(true);
        vo.setPositionList(false);
        vo.createDesc("en").setName("Position");

        TemplateParam entity = new TemplateParam(vo);

        assertEquals("pos", entity.getParamId());
        assertEquals("text", entity.getType());
        assertTrue(entity.isMandatory());
        assertFalse(entity.isPositionList());

        assertEquals(1, entity.getDescs().size());
        TemplateParamDesc enDesc = entity.getDesc("en");
        assertEquals("Position", enDesc.getName());
        assertEquals("en", enDesc.getLang());
        // The desc must be wired back to its owning entity.
        assertEquals(entity, enDesc.getEntity());
    }

    /** 11. TemplateParam.toVo() round-trips the scalar fields plus the descriptions. */
    @Test
    public void templateParamToVo_roundTripsFieldsAndDescs() {
        TemplateParam entity = new TemplateParam();
        entity.setParamId("name");
        entity.setType("aton_name");
        entity.setMandatory(false);
        entity.setPositionList(true);
        entity.createDesc("da").setName("Navn");

        TemplateParamVo vo = entity.toVo(DataFilter.get());

        assertEquals("name", vo.getParamId());
        assertEquals("aton_name", vo.getType());
        assertFalse(vo.isMandatory());
        assertTrue(vo.isPositionList());

        assertEquals(1, vo.getDescs().size());
        TemplateParamDescVo descVo = vo.getDescs().get(0);
        assertEquals("da", descVo.getLang());
        assertEquals("Navn", descVo.getName());
    }

    /** 12. TemplateParam.hasChanged(): false for an identical param, true when paramId or mandatory differ. */
    @Test
    public void templateParamHasChanged_detectsDifferences() {
        TemplateParam base = new TemplateParam();
        base.setParamId("p");
        base.setType("text");
        base.setMandatory(true);
        base.setPositionList(false);

        TemplateParam identical = new TemplateParam();
        identical.setParamId("p");
        identical.setType("text");
        identical.setMandatory(true);
        identical.setPositionList(false);
        assertFalse(base.hasChanged(identical), "Identical params must not be reported as changed");

        TemplateParam differentId = new TemplateParam();
        differentId.setParamId("other");
        differentId.setType("text");
        differentId.setMandatory(true);
        differentId.setPositionList(false);
        assertTrue(base.hasChanged(differentId), "A differing paramId must be reported as changed");

        TemplateParam differentMandatory = new TemplateParam();
        differentMandatory.setParamId("p");
        differentMandatory.setType("text");
        differentMandatory.setMandatory(false);
        differentMandatory.setPositionList(false);
        assertTrue(base.hasChanged(differentMandatory),
                "A differing mandatory flag must be reported as changed");
    }

    /* ===================================================================== */
    /* JACKSON POLYMORPHISM                                                  */
    /* ===================================================================== */

    /**
     * 13. A ListParamTypeVo serializes with the "type":"LIST" discriminator and round-trips
     * back into a ListParamTypeVo through the ParamTypeVo base type.
     */
    @Test
    public void listParamTypeVoRoundTripsThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        DictionaryEntryVo value = new DictionaryEntryVo();
        value.setKey("a");

        ListParamTypeVo vo = new ListParamTypeVo();
        vo.setName("L");
        vo.setValues(new ArrayList<>(List.of(value)));

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"LIST\""),
                "JSON must carry the LIST type discriminator: " + json);

        ParamTypeVo back = mapper.readValue(json, ParamTypeVo.class);
        assertTrue(back instanceof ListParamTypeVo);
        assertEquals("L", back.getName());
        assertEquals(1, ((ListParamTypeVo) back).getValues().size());
        assertEquals("a", ((ListParamTypeVo) back).getValues().get(0).getKey());
    }

    /**
     * 14. A CompositeParamTypeVo serializes with the "type":"COMPOSITE" discriminator and round-trips
     * back into a CompositeParamTypeVo through the ParamTypeVo base type.
     */
    @Test
    public void compositeParamTypeVoRoundTripsThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        TemplateParamVo paramVo = new TemplateParamVo();
        paramVo.setParamId("p1");
        paramVo.setType("text");

        CompositeParamTypeVo vo = new CompositeParamTypeVo();
        vo.setName("C");
        vo.setTemplateParams(new ArrayList<>(List.of(paramVo)));

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"COMPOSITE\""),
                "JSON must carry the COMPOSITE type discriminator: " + json);

        ParamTypeVo back = mapper.readValue(json, ParamTypeVo.class);
        assertTrue(back instanceof CompositeParamTypeVo);
        assertEquals("C", back.getName());
        assertEquals(1, ((CompositeParamTypeVo) back).getTemplateParams().size());
        assertEquals("p1", ((CompositeParamTypeVo) back).getTemplateParams().get(0).getParamId());
    }

    /**
     * 15. The {@code @JsonSubTypes} entry for "STANDARD" maps to the VO class
     * {@link StandardParamTypeVo} (not the entity {@code StandardParamType}), so a
     * {@code StandardParamTypeVo} round-trips through the polymorphic {@link ParamTypeVo} base type
     * exactly like the LIST and COMPOSITE subtypes.
     */
    @Test
    public void standardParamTypeVoRoundTripsThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setName("text");

        // Serialization emits the STANDARD discriminator via @JsonTypeName on the VO.
        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"STANDARD\""),
                "JSON must carry the STANDARD type discriminator: " + json);

        // Deserialization into the polymorphic base type now succeeds and yields a StandardParamTypeVo.
        ParamTypeVo back = mapper.readValue(json, ParamTypeVo.class);
        assertTrue(back instanceof StandardParamTypeVo);
        assertEquals("text", back.getName());
    }
}
