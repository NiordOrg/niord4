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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.Area;
import org.niord.core.area.AreaDesc;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryDesc;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves the areas, categories and charts of a message that arrives from elsewhere (a message archive, another Niord
 * server) against this Niord's own, and says which of them were left out.
 * <p>
 * Both message importers leave out a reference this Niord does not have; they share this code and write one line per
 * reference left out. The message itself is still imported: a warning without
 * its area is better for mariners than no warning.
 * <p>
 * A plain object rather than a bean: the importers build it from the services they already hold, so their constructors
 * and injected fields stay as they are.
 */
public class MessageBaseDataResolver {

    private final AreaService areaService;
    private final CategoryService categoryService;
    private final ChartService chartService;

    public MessageBaseDataResolver(AreaService areaService, CategoryService categoryService, ChartService chartService) {
        this.areaService = areaService;
        this.categoryService = categoryService;
        this.chartService = chartService;
    }

    /**
     * Replaces the message's areas, categories and charts with this Niord's, found by MRN or by name (areas and
     * categories, with their parents) or by chart number, and created when {@code createBaseData} is set.
     *
     * @param message the message whose references are resolved in place
     * @param createBaseData whether a reference this Niord does not have is created
     * @return one plain description per reference left out, in the message's order: areas, categories, charts; for
     *         example {@code area 'North Sea > Dogger Bank' (en)}, {@code category 'Wrecks' (en)} or {@code chart '101'}
     */
    public List<String> resolve(Message message, boolean createBaseData) {
        List<String> leftOut = new ArrayList<>();

        List<Area> areas = new ArrayList<>();
        for (Area area : message.getAreas()) {
            Area resolved = areaService.importArea(area, createBaseData, false);
            if (resolved != null) {
                areas.add(resolved);
            } else if (area != null) {
                leftOut.add("area " + describe(area, Area::getParent, MessageBaseDataResolver::areaNames));
            }
        }
        message.setAreas(areas);

        List<Category> categories = new ArrayList<>();
        for (Category category : message.getCategories()) {
            Category resolved = categoryService.importCategory(category, createBaseData, false);
            if (resolved != null) {
                categories.add(resolved);
            } else if (category != null) {
                leftOut.add("category " + describe(category, Category::getParent, MessageBaseDataResolver::categoryNames));
            }
        }
        message.setCategories(categories);

        List<Chart> charts = new ArrayList<>();
        for (Chart chart : message.getCharts()) {
            if (chart == null) {
                continue;
            }
            Chart resolved = chartService.importChart(chart, createBaseData, false);
            if (resolved != null) {
                charts.add(resolved);
            } else {
                leftOut.add("chart '" + StringUtils.defaultString(chart.getChartNumber()) + "'");
            }
        }
        message.setCharts(charts);

        return leftOut;
    }

    private static Map<String, String> areaNames(Area area) {
        Map<String, String> names = new LinkedHashMap<>();
        for (AreaDesc desc : area.getDescs()) {
            if (desc != null && StringUtils.isNotBlank(desc.getName())) {
                names.putIfAbsent(desc.getLang(), desc.getName());
            }
        }
        return names;
    }

    private static Map<String, String> categoryNames(Category category) {
        Map<String, String> names = new LinkedHashMap<>();
        for (CategoryDesc desc : category.getDescs()) {
            if (desc != null && StringUtils.isNotBlank(desc.getName())) {
                names.putIfAbsent(desc.getLang(), desc.getName());
            }
        }
        return names;
    }

    /**
     * The entity's path from its root, as {@code 'Root > Child' (lang)}: in English when the entity has an English
     * name, otherwise in the language of its first name that has a language; a parent without a name in that language
     * is given in its first one. A name whose language is null is a map key of its own and is used as a fallback; when
     * the entity has no language at all, the description has no {@code (lang)}. This helper never throws: a failure
     * here would fail the whole message import.
     */
    static <T> String describe(T entity, Function<T, T> parentOf, Function<T, Map<String, String>> namesOf) {
        Map<String, String> own = namesOf.apply(entity);
        // Stream.findFirst throws on a null element, and a desc's language may be null
        String lang = own.containsKey("en")
                ? "en"
                : own.keySet().stream().filter(Objects::nonNull).findFirst().orElse(null);

        LinkedList<String> path = new LinkedList<>();
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (T e = entity; e != null && seen.add(e); e = parentOf.apply(e)) {
            Map<String, String> names = namesOf.apply(e);
            String name = lang != null && names.containsKey(lang)
                    ? names.get(lang)
                    : names.values().stream().findFirst().orElse("(no name)");
            path.addFirst(name);
        }
        return "'" + String.join(" > ", path) + "'" + (lang == null ? "" : " (" + lang + ")");
    }
}
