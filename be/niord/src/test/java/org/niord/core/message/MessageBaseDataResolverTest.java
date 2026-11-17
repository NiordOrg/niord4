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

import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MessageBaseDataResolver}, which both message importers (the archive import and the Niord integration) use to
 * resolve a message's areas, categories and charts and to say which were left out (INT6). The archive import's IT
 * tries only an area; the category and the chart are tried here.
 */
class MessageBaseDataResolverTest {

    private final AreaService areaService = mock(AreaService.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final ChartService chartService = mock(ChartService.class);
    private final MessageBaseDataResolver resolver =
            new MessageBaseDataResolver(areaService, categoryService, chartService);

    private static Area area(Area parent, String... langAndNames) {
        Area area = new Area();
        area.setParent(parent);
        for (int i = 0; i < langAndNames.length; i += 2) {
            area.createDesc(langAndNames[i]).setName(langAndNames[i + 1]);
        }
        return area;
    }

    private static Category category(String lang, String name) {
        Category category = new Category();
        category.createDesc(lang).setName(name);
        return category;
    }

    private static Chart chart(String number) {
        Chart chart = new Chart();
        chart.setChartNumber(number);
        return chart;
    }

    @Test
    void theReferencesThisNiordHasAreKeptAndEachOneLeftOutIsDescribed() {
        Area knownArea = area(null, "en", "Denmark");
        Area storedArea = area(null, "en", "Denmark");
        Area unknownArea = area(area(null, "da", "Danmark", "en", "Denmark"), "da", "Kattegat da", "en", "Kattegat");
        Category knownCategory = category("en", "Buoys");
        Category storedCategory = category("en", "Buoys");
        Category unknownCategory = category("en", "Wrecks");
        Chart knownChart = chart("102");
        Chart storedChart = chart("102");
        Chart unknownChart = chart("101");

        Message message = new Message();
        message.setAreas(new ArrayList<>(List.of(unknownArea, knownArea)));
        message.setCategories(new ArrayList<>(List.of(knownCategory, unknownCategory)));
        message.setCharts(new ArrayList<>(List.of(unknownChart, knownChart)));

        when(areaService.importArea(same(knownArea), anyBoolean(), anyBoolean())).thenReturn(storedArea);
        when(categoryService.importCategory(same(knownCategory), anyBoolean(), anyBoolean())).thenReturn(storedCategory);
        when(chartService.importChart(same(knownChart), anyBoolean(), anyBoolean())).thenReturn(storedChart);

        List<String> leftOut = resolver.resolve(message, false);

        assertEquals(List.of("area 'Denmark > Kattegat' (en)", "category 'Wrecks' (en)", "chart '101'"), leftOut);
        assertEquals(1, message.getAreas().size());
        assertSame(storedArea, message.getAreas().getFirst(), "the stored area replaces the template");
        assertEquals(1, message.getCategories().size());
        assertSame(storedCategory, message.getCategories().getFirst(), "the stored category replaces the template");
        assertEquals(1, message.getCharts().size());
        assertSame(storedChart, message.getCharts().getFirst(), "the stored chart replaces the template");
        verify(areaService).importArea(unknownArea, false, false);
        verify(categoryService).importCategory(unknownCategory, false, false);
        verify(chartService).importChart(unknownChart, false, false);
    }

    @Test
    void createBaseDataIsPassedOnAndNothingIsLeftOutWhenEverythingResolves() {
        Area template = area(null, "en", "Somewhere");
        Area created = area(null, "en", "Somewhere");
        Message message = new Message();
        message.setAreas(new ArrayList<>(List.of(template)));
        when(areaService.importArea(template, true, false)).thenReturn(created);

        assertEquals(List.of(), resolver.resolve(message, true));
        assertEquals(List.of(created), message.getAreas());
    }

    @Test
    void aNameIsGivenInTheFirstLanguageWithoutEnglishAndAParentWithoutThatLanguageInItsOwn() {
        Area unknown = area(area(null, "en", "Greenland"), "da", "Diskobugten");
        Category nameless = new Category();
        Message message = new Message();
        message.setAreas(new ArrayList<>(List.of(unknown)));
        message.setCategories(new ArrayList<>(List.of(nameless)));

        assertEquals(List.of("area 'Greenland > Diskobugten' (da)", "category '(no name)'"),
                resolver.resolve(message, false));
    }

    @Test
    void aNameWithoutALanguageIsDescribedAndNeverThrows() {
        // A desc whose language is null is kept as a name; the description must not fail on it (Stream.findFirst
        // throws on a null element), or the whole message import fails
        Area noLangArea = area(area(null, null, "Parent"), null, "Somewhere");
        Category noLangCategory = category(null, "Wrecks");
        Area mixedArea = area(null, null, "Unnamed language", "da", "Kattegat");
        Message message = new Message();
        message.setAreas(new ArrayList<>(List.of(noLangArea, mixedArea)));
        message.setCategories(new ArrayList<>(List.of(noLangCategory)));

        assertEquals(List.of("area 'Parent > Somewhere'", "area 'Kattegat' (da)", "category 'Wrecks'"),
                resolver.resolve(message, false));
    }
}
