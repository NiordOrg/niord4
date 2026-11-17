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
package org.niord.core.category;

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainMapper;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.model.DataFilter;
import org.niord.model.message.CategoryVo;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CategoryMapper}: the projection, recursion, template-param mapping and the
 * {@code Category -> Domain} edge of the cyclic pair that were previously embedded in
 * {@code Category.toVo(...)} / {@code Category.updateCategory(...)}. The {@link DomainMapper}
 * collaborator is wired by hand (a bare domain exercises the edge without touching domain sub-mappers).
 */
public class CategoryMapperTest {

    private final CategoryMapper mapper = new CategoryMapper();

    {
        mapper.domainMapper = new DomainMapper();
    }

    private static Category category(int id, String name) {
        Category c = new Category();
        c.setId(id);
        c.createDesc("en").setName(name);
        return c;
    }

    @Test
    public void baseVoCarriesIdMrnActiveAndDescs() {
        Category c = category(1, "Warnings");
        c.setMrn("urn:mrn:cat");
        c.setActive(true);

        CategoryVo vo = mapper.toVo(c, CategoryVo.class, DataFilter.get().lang("en"));

        assertEquals(Integer.valueOf(1), vo.getId());
        assertEquals("urn:mrn:cat", vo.getMrn());
        assertEquals(true, vo.isActive());
        assertEquals(1, vo.getDescs().size());
        assertEquals("Warnings", vo.getDescs().get(0).getName());
    }

    @Test
    public void systemVoCopiesTypeSortOrderAndScriptPaths() {
        Category c = category(1, "T");
        c.setType(CategoryType.TEMPLATE);
        c.setSiblingSortOrder(5.0);
        c.setAtonFilter("aton-filter");
        c.setMessageId("msg-1");
        c.getStdTemplateFields().add("field-a");

        SystemCategoryVo vo = mapper.toVo(c, SystemCategoryVo.class, DataFilter.get());

        assertEquals(CategoryType.TEMPLATE, vo.getType());
        assertEquals(Double.valueOf(5.0), vo.getSiblingSortOrder());
        assertEquals("aton-filter", vo.getAtonFilter());
        assertEquals("msg-1", vo.getMessageId());
        assertTrue(vo.getStdTemplateFields().contains("field-a"));
    }

    @Test
    public void includeChildrenRecurses() {
        Category parent = category(1, "P");
        parent.addChild(category(2, "C1"));
        parent.addChild(category(3, "C2"));

        SystemCategoryVo vo = mapper.toVo(parent, SystemCategoryVo.class,
                DataFilter.get().fields(DataFilter.CHILDREN));

        assertEquals(2, vo.getChildren().size());
    }

    @Test
    public void includeParentRecurses() {
        Category parent = category(42, "P");
        parent.setMrn("urn:mrn:parent");
        Category child = category(2, "C");
        child.setParent(parent);

        SystemCategoryVo vo = mapper.toVo(child, SystemCategoryVo.class,
                DataFilter.get().fields(DataFilter.PARENT));

        assertEquals(Integer.valueOf(42), vo.getParent().getId());
        assertEquals("urn:mrn:parent", vo.getParent().getMrn());
    }

    @Test
    public void templateParamsAreMapped() {
        Category c = category(1, "T");
        c.setType(CategoryType.TEMPLATE);
        TemplateParam p = new TemplateParam();
        p.setParamId("position");
        p.setType("text");
        p.setMandatory(true);
        c.setTemplateParams(List.of(p));

        SystemCategoryVo vo = mapper.toVo(c, SystemCategoryVo.class, DataFilter.get());

        assertEquals(1, vo.getTemplateParams().size());
        assertEquals("position", vo.getTemplateParams().get(0).getParamId());
        assertEquals("text", vo.getTemplateParams().get(0).getType());
        assertTrue(vo.getTemplateParams().get(0).isMandatory());
    }

    @Test
    public void domainsAreMappedThroughTheCyclicEdge() {
        Category c = category(1, "T");
        Domain d = new Domain();
        d.setDomainId("dma-nw");
        d.setActive(true);
        c.setDomains(List.of(d));

        SystemCategoryVo vo = mapper.toVo(c, SystemCategoryVo.class, DataFilter.get());

        assertEquals(1, vo.getDomains().size());
        assertEquals("dma-nw", vo.getDomains().get(0).getDomainId());
    }

    @Test
    public void toEntityCopiesSystemFieldsAndTemplateParams() {
        SystemCategoryVo vo = new SystemCategoryVo();
        vo.setId(9);
        vo.setMrn("urn:mrn:y");
        vo.setActive(true);
        vo.setType(CategoryType.TEMPLATE);
        vo.setSiblingSortOrder(2.0);
        TemplateParamVo p = new TemplateParamVo();
        p.setParamId("p1");
        p.setType("text");
        vo.setTemplateParams(Arrays.asList(p));

        Category c = mapper.toEntity(vo, DataFilter.get());

        assertEquals(Integer.valueOf(9), c.getId());
        assertEquals("urn:mrn:y", c.getMrn());
        assertEquals(CategoryType.TEMPLATE, c.getType());
        assertEquals(2.0, c.getSiblingSortOrder(), 0.0001);
        assertEquals(1, c.getTemplateParams().size());
        assertEquals("p1", c.getTemplateParams().get(0).getParamId());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null, SystemCategoryVo.class, DataFilter.get()));
    }
}
