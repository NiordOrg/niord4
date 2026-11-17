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

import org.hibernate.collection.spi.PersistentBag;
import org.junit.jupiter.api.Test;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Area#hasChanged(Area)}, which decides whether an area differs from a
 * template across the mutable, business-relevant fields (sort order, mrn, type, active, descriptions,
 * geometry, message sorting, origin and editor fields) — deliberately ignoring id, timestamps and tree
 * position.
 */
public class AreaHasChangedTest {

    /** A fully-populated reference area; each test mutates one field on the template. */
    private Area base() {
        Area a = AreaTestFactory.area(1, "da", "Danmark");
        a.setMrn("urn:mrn:dma:area:dk");
        a.setType(AreaType.COUNTRY);
        a.setSiblingSortOrder(1.0);
        a.setActive(true);
        a.setGeometry(AreaTestFactory.point(56.0, 10.0));
        a.setMessageSorting(AreaMessageSorting.CW);
        a.setOriginLatitude(55.5f);
        a.setOriginLongitude(11.25f);
        a.setOriginAngle(30);
        a.setEditorFields(new ArrayList<>(List.of("title", "charts")));
        return a;
    }

    @Test
    public void identicalAreasHaveNotChanged() {
        assertFalse(base().hasChanged(base()));
    }

    @Test
    public void differentSiblingSortOrderChanged() {
        Area template = base();
        template.setSiblingSortOrder(2.0);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentMrnChanged() {
        Area template = base();
        template.setMrn("urn:mrn:dma:area:other");
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentTypeChanged() {
        Area template = base();
        template.setType(AreaType.FIRING_AREA);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentActiveFlagChanged() {
        Area template = base();
        template.setActive(false);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void additionalDescriptionChanged() {
        Area template = base();
        template.checkCreateDesc("en").setName("Denmark");
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void renamedDescriptionChanged() {
        Area template = base();
        template.getDesc("da").setName("Kongeriget Danmark");
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void descriptionLanguageMissingInTemplateChanged() {
        // Same number of descriptions, but a different set of languages.
        Area original = base();
        original.checkCreateDesc("en").setName("Denmark");

        Area template = base();
        template.checkCreateDesc("fr").setName("Danemark");

        assertTrue(original.hasChanged(template));
    }

    @Test
    public void geometryNullToNonNullChanged() {
        Area original = base();
        original.setGeometry(null);
        assertTrue(original.hasChanged(base()));
    }

    @Test
    public void differentGeometryChanged() {
        Area template = base();
        template.setGeometry(AreaTestFactory.point(10.0, 10.0));
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void equalGeometryHasNotChanged() {
        // Distinct Geometry instances with identical coordinates are topologically equal.
        assertFalse(base().hasChanged(base()));
    }

    @Test
    public void differentMessageSortingChanged() {
        Area template = base();
        template.setMessageSorting(AreaMessageSorting.NS);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentOriginLatitudeChanged() {
        Area template = base();
        template.setOriginLatitude(56.25f);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentOriginLongitudeChanged() {
        Area template = base();
        template.setOriginLongitude(12.5f);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentOriginAngleChanged() {
        Area template = base();
        template.setOriginAngle(45);
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void differentEditorFieldsChanged() {
        Area template = base();
        template.getEditorFields().remove("charts");
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void reorderedEditorFieldsChanged() {
        Area template = base();
        template.setEditorFields(new ArrayList<>(List.of("charts", "title")));
        assertTrue(base().hasChanged(template));
    }

    @Test
    public void editorFieldsInAHibernateBagCompareByContent() {
        // A stored area holds its editor fields in a PersistentBag, whose equals() is identity
        Area original = base();
        original.setEditorFields(new PersistentBag<>(null, new ArrayList<>(List.of("title", "charts"))));
        assertFalse(original.hasChanged(base()));
    }

    @Test
    public void missingEditorFieldsEqualAnEmptyList() {
        Area original = base();
        original.setEditorFields(new ArrayList<>());
        Area template = base();
        template.setEditorFields(null);
        assertFalse(original.hasChanged(template));
    }
}
