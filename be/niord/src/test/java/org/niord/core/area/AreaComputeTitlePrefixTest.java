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
package org.niord.core.area;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link Area#computeAreaTitlePrefix(List, String)}.
 *
 * <p>The method emits area lineages top-to-bottom, level by level, de-duplicating shared ancestors
 * within each level and skipping blank names. These tests pin that observable contract.
 */
public class AreaComputeTitlePrefixTest {

    @Test
    public void emptyListReturnsEmptyString() {
        assertEquals("", Area.computeAreaTitlePrefix(new ArrayList<>(), "da"));
    }

    @Test
    public void singleAreaNoParent() {
        List<Area> areas = Arrays.asList(AreaTestFactory.area(1, "da", "Danmark"));
        assertEquals("Danmark.", Area.computeAreaTitlePrefix(areas, "da"));
    }

    @Test
    public void singleLineageOrdersTopToBottom() {
        Area laeso = AreaTestFactory.area(3, "da", "Læsø");
        Area kattegat = AreaTestFactory.area(2, "da", "Kattegat");
        Area danmark = AreaTestFactory.area(1, "da", "Danmark");
        laeso.setParent(kattegat);
        kattegat.setParent(danmark);

        assertEquals("Danmark. Kattegat. Læsø.",
                Area.computeAreaTitlePrefix(Arrays.asList(laeso), "da"));
    }

    @Test
    public void dedupesSharedAncestorsPerLevel() {
        // Mirrors the legacy AreaTest happy path: two leaves sharing the ancestor "Danmark".
        List<Area> areas = new ArrayList<>();

        Area laeso = AreaTestFactory.area(4, "da", "Læsø");
        Area kattegat = AreaTestFactory.area(2, "da", "Kattegat");
        kattegat.setParent(AreaTestFactory.area(1, "da", "Danmark"));
        laeso.setParent(kattegat);
        areas.add(laeso);

        Area skagerak = AreaTestFactory.area(3, "da", "Skagerak");
        skagerak.setParent(AreaTestFactory.area(1, "da", "Danmark"));
        areas.add(skagerak);

        assertEquals("Danmark. Kattegat. Skagerak. Læsø.",
                Area.computeAreaTitlePrefix(areas, "da"));
    }

    @Test
    public void javadocExampleOrdersCountriesThenChildren() {
        // The example from the method's javadoc, with two countries.
        Area kattegat = AreaTestFactory.area(2, "da", "Kattegat");
        kattegat.setParent(AreaTestFactory.area(1, "da", "Danmark"));
        Area skagerak = AreaTestFactory.area(3, "da", "Skagerak");
        skagerak.setParent(AreaTestFactory.area(1, "da", "Danmark"));
        Area hamborg = AreaTestFactory.area(5, "da", "Hamborg");
        hamborg.setParent(AreaTestFactory.area(4, "da", "Tyskland"));

        assertEquals("Danmark. Tyskland. Kattegat. Skagerak. Hamborg.",
                Area.computeAreaTitlePrefix(Arrays.asList(kattegat, skagerak, hamborg), "da"));
    }

    @Test
    public void blankNameInLineageIsSkipped() {
        Area laeso = AreaTestFactory.area(3, "da", "Læsø");
        Area blank = AreaTestFactory.area(2, "da", "");
        Area danmark = AreaTestFactory.area(1, "da", "Danmark");
        laeso.setParent(blank);
        blank.setParent(danmark);

        assertEquals("Danmark. Læsø.",
                Area.computeAreaTitlePrefix(Arrays.asList(laeso), "da"));
    }

    @Test
    public void nameAlreadyEndingWithDotIsNotDoubled() {
        assertEquals("N.W.",
                Area.computeAreaTitlePrefix(Arrays.asList(AreaTestFactory.area(1, "da", "N.W.")), "da"));
    }

    @Test
    public void picksRequestedLanguage() {
        Area area = new Area();
        area.setId(1);
        area.checkCreateDesc("da").setName("Danmark");
        area.checkCreateDesc("en").setName("Denmark");

        assertEquals("Denmark.", Area.computeAreaTitlePrefix(Arrays.asList(area), "en"));
        assertEquals("Danmark.", Area.computeAreaTitlePrefix(Arrays.asList(area), "da"));
    }

    @Test
    public void unknownLanguageFallsBackToFirstDescription() {
        // Only a Danish description exists; an unknown language falls back to the first available desc.
        assertEquals("Danmark.",
                Area.computeAreaTitlePrefix(Arrays.asList(AreaTestFactory.area(1, "da", "Danmark")), "fr"));
    }

    @Test
    public void descLessLineageLevelIsSkipped() {
        // A middle lineage level with NO descriptions must be skipped (like a blank name), not crash the
        // whole title computation. Before the fix, getDescs(filter).get(0) threw IndexOutOfBoundsException.
        Area laeso = AreaTestFactory.area(3, "da", "Læsø");
        Area middle = AreaTestFactory.area(2);          // no descriptions at all
        Area danmark = AreaTestFactory.area(1, "da", "Danmark");
        laeso.setParent(middle);
        middle.setParent(danmark);

        assertEquals("Danmark. Læsø.",
                Area.computeAreaTitlePrefix(Arrays.asList(laeso), "da"));
    }

    @Test
    public void singleDescLessAreaReturnsEmptyString() {
        // A lone area with no descriptions contributes no lineage names — empty prefix, no exception.
        assertEquals("",
                Area.computeAreaTitlePrefix(Arrays.asList(AreaTestFactory.area(1)), "da"));
    }
}
