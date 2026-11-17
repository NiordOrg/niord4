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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link Area#createDesc(String)} and the inherited {@code checkCreateDesc} behaviour,
 * including the bidirectional Area &lt;-&gt; AreaDesc back-reference.
 */
public class AreaCreateDescTest {

    @Test
    public void createDescAddsToDescriptionList() {
        Area area = new Area();
        area.createDesc("da");
        assertEquals(1, area.getDescs().size());
    }

    @Test
    public void createDescSetsLanguageAndBackReference() {
        Area area = new Area();
        AreaDesc desc = area.createDesc("da");

        assertEquals("da", desc.getLang());
        assertSame(area, desc.getEntity(), "createDesc must wire the desc back to its owning area");
    }

    @Test
    public void checkCreateDescReusesExistingDescriptionForSameLanguage() {
        Area area = new Area();
        area.checkCreateDesc("da").setName("Danmark");
        AreaDesc reused = area.checkCreateDesc("da");

        assertEquals(1, area.getDescs().size());
        assertEquals("Danmark", reused.getName());
    }

    @Test
    public void createDescAlwaysAppendsEvenForDuplicateLanguage() {
        // createDesc (unlike checkCreateDesc) does not de-duplicate by language.
        Area area = new Area();
        area.createDesc("da");
        area.createDesc("da");
        assertEquals(2, area.getDescs().size());
    }
}
