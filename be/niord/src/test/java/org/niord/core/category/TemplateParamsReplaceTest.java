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
package org.niord.core.category;

import org.junit.jupiter.api.Test;
import org.niord.core.bootstrap.Given;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first bootstrap's replace of template parameters ({@link TemplateExecutionService#replaceTemplateParams},
 * docs/code-data-config-plan.md §7.2), shared by categories and composite parameter types, and the refusal of a
 * parameter type whose kind a later archive changes ({@link TemplateExecutionService#checkSameKind}). The kind change
 * is pinned here and not by a failing second run in an IT: a database is bootstrapped once.
 */
class TemplateParamsReplaceTest {

    private static TemplateParam param(String paramId, String type, boolean mandatory, boolean positionList,
                                       String englishName) {
        TemplateParam param = new TemplateParam();
        param.setParamId(paramId);
        param.setType(type);
        param.setMandatory(mandatory);
        param.setPositionList(positionList);
        param.createDesc("en").setName(englishName);
        return param;
    }

    private static Given given(String json) {
        return Given.of(JsonMapper.builder().build().readTree(json));
    }

    @Test
    void aMatchByParamIdThenByOrderReplacesWhatTheFileGives() {
        List<TemplateParam> rows = new ArrayList<>(List.of(
                param("p1", "text", false, false, "First"),
                param("p2", "text", true, true, "Second"),
                param("p1", "text", false, false, "First again")));
        List<TemplateParam> file = List.of(
                param("p1", "number", false, false, "FIRST"),
                param("p2", "text", false, false, "Second"),
                param("p1", "text", false, true, "First again"));
        // p1#0 gives its type; p2 leaves its flags out (the VO's false is not given); p1#1 gives its position list
        Given given = given("{\"templateParams\": ["
                + "{\"paramId\": \"p1\", \"type\": \"number\", \"descs\": [{\"lang\": \"en\", \"name\": \"FIRST\"}]},"
                + "{\"paramId\": \"p2\", \"descs\": [{\"lang\": \"en\", \"name\": \"Second\"}]},"
                + "{\"paramId\": \"p1\", \"positionList\": true}]}");
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        TemplateParam secondP1 = rows.get(2);

        TemplateExecutionService.replaceTemplateParams("", rows, file, given, added, replaced);

        assertEquals(List.of("p1", "p2", "p1"), rows.stream().map(TemplateParam::getParamId).toList());
        assertEquals("number", rows.get(0).getType());
        assertEquals("FIRST", rows.get(0).getDesc("en").getName());
        assertTrue(rows.get(1).isMandatory(), "a flag left out is kept");
        assertTrue(rows.get(1).isPositionList(), "a flag left out is kept");
        assertSame(secondP1, rows.get(2), "the second p1 is matched by its order and changed in place");
        assertTrue(secondP1.isPositionList());
        assertEquals("First again", secondP1.getDesc("en").getName());
        assertEquals(List.of(), added);
        assertEquals(List.of("the p1 type text → number", "the p1 en name First → FIRST",
                "the p1 position-list flag false → true"), replaced);
    }

    @Test
    void aFlagGivenFalseReplacesTrue() {
        List<TemplateParam> rows = new ArrayList<>(List.of(param("m", "text", true, true, "M")));
        List<String> replaced = new ArrayList<>();

        TemplateExecutionService.replaceTemplateParams("", rows,
                List.of(param("m", "text", false, false, "M")),
                given("{\"templateParams\": [{\"paramId\": \"m\", \"mandatory\": false, \"positionList\": false}]}"),
                new ArrayList<>(), replaced);

        assertFalse(rows.get(0).isMandatory());
        assertFalse(rows.get(0).isPositionList());
        assertEquals(List.of("the m mandatory flag true → false", "the m position-list flag true → false"),
                replaced);
    }

    @Test
    void aNewParamIdIsAppendedAndARowTheFileLacksIsKept() {
        List<TemplateParam> rows = new ArrayList<>(List.of(param("a", "text", false, false, "A"),
                param("b", "text", false, false, "B")));
        TemplateParam c = param("c", "text", false, false, "C");
        c.createDesc("da").setName("C da");
        List<String> added = new ArrayList<>();

        TemplateExecutionService.replaceTemplateParams("template parameter ", rows,
                List.of(param("a", "text", false, false, "A"), c),
                given("{\"templateParams\": [{\"paramId\": \"a\"}, {\"paramId\": \"c\", \"type\": \"text\"}]}"),
                added, new ArrayList<>());

        assertEquals(List.of("a", "b", "c"), rows.stream().map(TemplateParam::getParamId).toList());
        assertSame(c, rows.get(2));
        assertEquals(List.of("template parameter c"), added);
    }

    @Test
    void aLanguageTheRowLacksIsAddedAndOneTheFileLeavesOutIsKept() {
        TemplateParam row = param("p", "text", false, false, "P");
        row.createDesc("fr").setName("P fr");
        TemplateParam file = new TemplateParam();
        file.setParamId("p");
        file.createDesc("da").setName("P da");
        List<String> added = new ArrayList<>();

        TemplateExecutionService.replaceTemplateParams("", new ArrayList<>(List.of(row)), List.of(file),
                given("{\"templateParams\": [{\"paramId\": \"p\", "
                        + "\"descs\": [{\"lang\": \"da\", \"name\": \"P da\"}]}]}"),
                added, new ArrayList<>());

        assertEquals("P da", row.getDesc("da").getName());
        assertEquals("P fr", row.getDesc("fr").getName());
        assertEquals("P", row.getDesc("en").getName());
        assertEquals("text", row.getType(), "the type left out is kept");
        assertEquals(List.of("p da"), added);
    }

    @Test
    void anEqualFileChangesNothingAndKeepsTheRows() {
        TemplateParam one = param("p1", "text", true, false, "First");
        TemplateParam two = param("p2", "number", false, true, "Second");
        List<TemplateParam> rows = new ArrayList<>(List.of(one, two));
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();

        TemplateExecutionService.replaceTemplateParams("", rows,
                List.of(param("p1", "text", true, false, "First"), param("p2", "number", false, true, "Second")),
                given("{\"templateParams\": ["
                        + "{\"paramId\": \"p1\", \"type\": \"text\", \"mandatory\": true, \"positionList\": false,"
                        + " \"descs\": [{\"lang\": \"en\", \"name\": \"First\"}]},"
                        + "{\"paramId\": \"p2\", \"type\": \"number\", \"mandatory\": false, \"positionList\": true,"
                        + " \"descs\": [{\"lang\": \"en\", \"name\": \"Second\"}]}]}"),
                added, replaced);

        assertEquals(List.of(), added);
        assertEquals(List.of(), replaced);
        assertSame(one, rows.get(0));
        assertSame(two, rows.get(1));
        assertEquals(2, rows.size());
        assertEquals(1, one.getDescs().size());
    }

    @Test
    void aKindChangeBetweenCompositeAndListIsRefusedNamingBothArchives() {
        CompositeParamType composite = new CompositeParamType();
        composite.setName("it_kind");
        ListParamType list = new ListParamType();
        list.setName("it_kind");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TemplateExecutionService.checkSameKind(composite, list, 0, 1));
        assertTrue(e.getMessage().contains("it_kind") && e.getMessage().contains("composite type in archive 0")
                && e.getMessage().contains("list type in archive 1"), e.getMessage());

        assertThrows(IllegalStateException.class, () -> TemplateExecutionService.checkSameKind(list, composite, 0, 1));
        TemplateExecutionService.checkSameKind(composite, new CompositeParamType(), 0, 1);
        TemplateExecutionService.checkSameKind(list, new ListParamType(), 0, 1);
    }
}
