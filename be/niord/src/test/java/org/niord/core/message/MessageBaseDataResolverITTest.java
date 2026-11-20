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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.MessageVo;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageBaseDataResolver} against the seeded database of the {@link ITTest}
 * infrastructure: a message read from a file or from another Niord, through {@code new Message(vo)}, finds the nested
 * category it names.
 * <p>
 * {@code new Message(vo)} builds each category without its parent, even when the file names one, so the resolver
 * hands {@link CategoryService#importCategory(Category, boolean, boolean)} a parentless template. The message, archive
 * and integration imports all go this way.
 * <p>
 * Each test runs in a transaction that is rolled back.
 * <p>
 * This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageBaseDataResolverITTest extends ITTest {

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    @Test
    @Transactional
    void aMessageFindsItsNestedCategory_withoutCreate() {
        assertResolvesToTheNestedCategory(false);
    }

    @Test
    @Transactional
    void aMessageFindsItsNestedCategory_withCreate_andMakesNoRoot() {
        assertResolvesToTheNestedCategory(true);
    }

    private void assertResolvesToTheNestedCategory(boolean createBaseData) {
        String tag = UUID.randomUUID().toString();
        String parentName = "IT Parent " + tag;
        String childName = "IT Child " + tag;
        Category parent = categoryService.createCategory(newCategory(parentName), null);
        Category child = categoryService.createCategory(newCategory(childName), parent.getId());
        em.flush();

        // The message as a file or another Niord gives it: its category with the parent
        CategoryVo parentVo = new CategoryVo();
        parentVo.createDesc("en").setName(parentName);
        CategoryVo childVo = new CategoryVo();
        childVo.createDesc("en").setName(childName);
        childVo.setParent(parentVo);
        MessageVo vo = new MessageVo();
        vo.setCategories(List.of(childVo));
        Message message = new Message(vo);

        List<String> leftOut = new MessageBaseDataResolver(areaService, categoryService, chartService)
                .resolve(message, createBaseData);

        assertTrue(leftOut.isEmpty(), "nothing is left out: " + leftOut);
        assertEquals(1, message.getCategories().size());
        assertEquals(child.getId(), message.getCategories().getFirst().getId(),
                "the message is filed under the existing nested category");
        assertNull(categoryService.findRootByName(childName, "en"), "no root of the child's name is made");
    }

    private static Category newCategory(String englishName) {
        Category category = new Category();
        category.checkCreateDesc("en").setName(englishName);
        return category;
    }
}
