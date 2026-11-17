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
package org.niord.core.category;

import org.junit.jupiter.api.Test;
import org.niord.core.category.vo.CompositeParamTypeVo;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.core.category.vo.StandardParamTypeVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.model.DataFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParamTypeMapper}: the polymorphic {@code toVo()} dispatch and the conversion
 * constructors that were previously embedded in the {@link StandardParamType}, {@link ListParamType} and
 * {@link CompositeParamType} entities (plus the {@code ParamTypeVo.toEntity()} factory).
 *
 * <p>The tests are differential where the entity methods still exist: a representative entity is built, then
 * the mapper output is asserted to match the entity's own {@code toVo(...)} output field-by-field across the
 * nested graph. The {@code toEntity} side is verified by round-tripping back through the entity {@code toVo}.
 */
public class ParamTypeMapperTest {

    private final ParamTypeMapper mapper = new ParamTypeMapper();

    /* ----------------------------------------------------------------------------- */
    /* StandardParamType                                                             */
    /* ----------------------------------------------------------------------------- */

    @Test
    public void standardToVoMatchesEntity() {
        StandardParamType e = new StandardParamType("text");
        e.setId(7);

        // polymorphic dispatch on the abstract type
        ParamTypeVo vo = mapper.toVo((ParamType) e, DataFilter.get());

        assertTrue(vo instanceof StandardParamTypeVo);
        StandardParamTypeVo expected = e.toVo(DataFilter.get());
        assertEquals(expected.getId(), vo.getId());
        assertEquals(expected.getName(), vo.getName());
        assertEquals(Integer.valueOf(7), vo.getId());
        assertEquals("text", vo.getName());
    }

    @Test
    public void standardToEntityRoundTrips() {
        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setId(3);
        vo.setName("number");

        ParamType e = mapper.toEntity((ParamTypeVo) vo);

        assertTrue(e instanceof StandardParamType);
        assertEquals(Integer.valueOf(3), e.getId());
        assertEquals("number", e.getName());
    }

    /* ----------------------------------------------------------------------------- */
    /* ListParamType                                                                 */
    /* ----------------------------------------------------------------------------- */

    private static DictionaryEntry dictEntry(int id, String key, String enValue) {
        DictionaryEntry de = new DictionaryEntry();
        de.setId(id);
        de.setKey(key);
        de.createDesc("en").setValue(enValue);
        return de;
    }

    @Test
    public void listToVoMatchesEntityFieldByField() {
        ListParamType e = new ListParamType();
        e.setId(11);
        e.setName("port-list");
        e.getValues().add(dictEntry(1, "cph", "Copenhagen"));
        e.getValues().add(dictEntry(2, "aar", "Aarhus"));

        DataFilter filter = DataFilter.get().lang("en");
        ListParamTypeVo expected = e.toVo(filter);
        ParamTypeVo vo = mapper.toVo((ParamType) e, filter);

        assertTrue(vo instanceof ListParamTypeVo);
        ListParamTypeVo listVo = (ListParamTypeVo) vo;

        assertEquals(expected.getId(), listVo.getId());
        assertEquals(expected.getName(), listVo.getName());
        assertEquals(Integer.valueOf(11), listVo.getId());
        assertEquals("port-list", listVo.getName());

        assertEquals(expected.getValues().size(), listVo.getValues().size());
        assertEquals(2, listVo.getValues().size());
        for (int i = 0; i < listVo.getValues().size(); i++) {
            DictionaryEntryVo exp = expected.getValues().get(i);
            DictionaryEntryVo act = listVo.getValues().get(i);
            assertEquals(exp.getId(), act.getId());
            assertEquals(exp.getKey(), act.getKey());
            assertEquals(exp.getDescs().get(0).getValue(), act.getDescs().get(0).getValue());
        }
        assertEquals("cph", listVo.getValues().get(0).getKey());
        assertEquals("Copenhagen", listVo.getValues().get(0).getDescs().get(0).getValue());
        assertEquals("aar", listVo.getValues().get(1).getKey());
    }

    @Test
    public void listToEntityRoundTrips() {
        ListParamTypeVo vo = new ListParamTypeVo();
        vo.setId(20);
        vo.setName("ports");
        DictionaryEntryVo entryVo = new DictionaryEntryVo();
        entryVo.setId(5);
        entryVo.setKey("cph");
        entryVo.createDesc("en").setValue("Copenhagen");
        vo.setValues(List.of(entryVo));

        ParamType e = mapper.toEntity((ParamTypeVo) vo);

        assertTrue(e instanceof ListParamType);
        ListParamType list = (ListParamType) e;
        assertEquals(Integer.valueOf(20), list.getId());
        assertEquals("ports", list.getName());
        assertEquals(1, list.getValues().size());
        assertEquals("cph", list.getValues().get(0).getKey());

        // round-trip the produced entity back to a VO and compare with the entity factory's own result
        ListParamTypeVo viaEntity = (ListParamTypeVo) new ListParamType(vo).toVo(DataFilter.get().lang("en"));
        ListParamTypeVo viaMapper = list.toVo(DataFilter.get().lang("en"));
        assertEquals(viaEntity.getValues().get(0).getKey(), viaMapper.getValues().get(0).getKey());
        assertEquals(viaEntity.getValues().get(0).getDescs().get(0).getValue(),
                viaMapper.getValues().get(0).getDescs().get(0).getValue());
    }

    /* ----------------------------------------------------------------------------- */
    /* CompositeParamType                                                            */
    /* ----------------------------------------------------------------------------- */

    private static TemplateParam templateParam(String id, String type, boolean mandatory, String enName) {
        TemplateParam p = new TemplateParam();
        p.setParamId(id);
        p.setType(type);
        p.setMandatory(mandatory);
        p.createDesc("en").setName(enName);
        return p;
    }

    @Test
    public void compositeToVoMatchesEntityFieldByField() {
        CompositeParamType e = new CompositeParamType();
        e.setId(33);
        e.setName("position");
        e.getTemplateParams().add(templateParam("lat", "number", true, "Latitude"));
        e.getTemplateParams().add(templateParam("lon", "number", false, "Longitude"));

        DataFilter filter = DataFilter.get().lang("en");
        CompositeParamTypeVo expected = e.toVo(filter);
        ParamTypeVo vo = mapper.toVo((ParamType) e, filter);

        assertTrue(vo instanceof CompositeParamTypeVo);
        CompositeParamTypeVo compVo = (CompositeParamTypeVo) vo;

        assertEquals(expected.getId(), compVo.getId());
        assertEquals(expected.getName(), compVo.getName());
        assertEquals(Integer.valueOf(33), compVo.getId());
        assertEquals("position", compVo.getName());

        assertEquals(expected.getTemplateParams().size(), compVo.getTemplateParams().size());
        assertEquals(2, compVo.getTemplateParams().size());
        for (int i = 0; i < compVo.getTemplateParams().size(); i++) {
            TemplateParamVo exp = expected.getTemplateParams().get(i);
            TemplateParamVo act = compVo.getTemplateParams().get(i);
            assertEquals(exp.getParamId(), act.getParamId());
            assertEquals(exp.getType(), act.getType());
            assertEquals(exp.isMandatory(), act.isMandatory());
            assertEquals(exp.isPositionList(), act.isPositionList());
            assertEquals(exp.getDescs().get(0).getName(), act.getDescs().get(0).getName());
        }
        assertEquals("lat", compVo.getTemplateParams().get(0).getParamId());
        assertTrue(compVo.getTemplateParams().get(0).isMandatory());
        assertEquals("Latitude", compVo.getTemplateParams().get(0).getDescs().get(0).getName());
        assertFalse(compVo.getTemplateParams().get(1).isMandatory());
    }

    @Test
    public void compositeToEntityRoundTrips() {
        CompositeParamTypeVo vo = new CompositeParamTypeVo();
        vo.setId(40);
        vo.setName("pos");
        TemplateParamVo p = new TemplateParamVo();
        p.setParamId("lat");
        p.setType("number");
        p.setMandatory(true);
        p.createDesc("en").setName("Latitude");
        vo.setTemplateParams(List.of(p));

        ParamType e = mapper.toEntity((ParamTypeVo) vo);

        assertTrue(e instanceof CompositeParamType);
        CompositeParamType comp = (CompositeParamType) e;
        assertEquals(Integer.valueOf(40), comp.getId());
        assertEquals("pos", comp.getName());
        assertEquals(1, comp.getTemplateParams().size());
        TemplateParam tp = comp.getTemplateParams().get(0);
        assertEquals("lat", tp.getParamId());
        assertEquals("number", tp.getType());
        assertTrue(tp.isMandatory());
        assertEquals("Latitude", tp.getDesc("en").getName());
    }

    /* ----------------------------------------------------------------------------- */
    /* DataFilter language projection                                                */
    /* ----------------------------------------------------------------------------- */

    @Test
    public void dataFilterLangFiltersTemplateParamDescs() {
        TemplateParam p = new TemplateParam();
        p.setParamId("lat");
        p.setType("number");
        p.createDesc("en").setName("Latitude");
        p.createDesc("da").setName("Breddegrad");
        CompositeParamType e = new CompositeParamType();
        e.setName("position");
        e.getTemplateParams().add(p);

        // With a language filter, only the matching desc survives (mirrors the entity behaviour)
        CompositeParamTypeVo expected = e.toVo(DataFilter.get().lang("da"));
        CompositeParamTypeVo filtered =
                (CompositeParamTypeVo) mapper.toVo((ParamType) e, DataFilter.get().lang("da"));
        assertEquals(expected.getTemplateParams().get(0).getDescs().size(),
                filtered.getTemplateParams().get(0).getDescs().size());
        assertEquals(1, filtered.getTemplateParams().get(0).getDescs().size());
        assertEquals("Breddegrad", filtered.getTemplateParams().get(0).getDescs().get(0).getName());

        // Without a language filter, both descs are present
        CompositeParamTypeVo all =
                (CompositeParamTypeVo) mapper.toVo((ParamType) e, DataFilter.get());
        assertEquals(2, all.getTemplateParams().get(0).getDescs().size());
    }

    /* ----------------------------------------------------------------------------- */
    /* null handling                                                                 */
    /* ----------------------------------------------------------------------------- */

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo((ParamType) null, DataFilter.get()));
        assertNull(mapper.toVo((StandardParamType) null, DataFilter.get()));
        assertNull(mapper.toVo((ListParamType) null, DataFilter.get()));
        assertNull(mapper.toVo((CompositeParamType) null, DataFilter.get()));
    }

    @Test
    public void nullVoMapsToNull() {
        assertNull(mapper.toEntity((ParamTypeVo) null));
        assertNull(mapper.toEntity((StandardParamTypeVo) null));
        assertNull(mapper.toEntity((ListParamTypeVo) null));
        assertNull(mapper.toEntity((CompositeParamTypeVo) null));
    }
}
