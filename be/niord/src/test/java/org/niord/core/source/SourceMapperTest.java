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

package org.niord.core.source;

import org.junit.jupiter.api.Test;
import org.niord.core.source.vo.SourceDescVo;
import org.niord.core.source.vo.SourceVo;
import org.niord.model.DataFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SourceMapper}, verifying that the extracted entity&lt;-&gt;VO
 * conversion preserves the behaviour of the original in-entity {@code toVo}/constructor logic.
 */
public class SourceMapperTest {

    private final SourceMapper mapper = new SourceMapper();

    /** Builds a representative source with two localized descriptions. */
    private Source newSource() {
        Source source = new Source();
        source.setId(42);
        source.setActive(true);

        SourceDesc en = source.createDesc("en");
        en.setName("English name");
        en.setAbbreviation("EN");

        SourceDesc da = source.createDesc("da");
        da.setName("Dansk navn");
        da.setAbbreviation("DA");

        return source;
    }

    @Test
    public void toVoCopiesBaseFieldsAndAllDescs() {
        SourceVo vo = mapper.toVo(newSource(), DataFilter.get().fields(DataFilter.ALL));

        assertEquals(Integer.valueOf(42), vo.getId());
        assertTrue(vo.isActive());

        assertEquals(2, vo.getDescs().size());

        SourceDescVo en = vo.getDescs().get(0);
        assertEquals("en", en.getLang());
        assertEquals("English name", en.getName());
        assertEquals("EN", en.getAbbreviation());

        SourceDescVo da = vo.getDescs().get(1);
        assertEquals("da", da.getLang());
        assertEquals("Dansk navn", da.getName());
        assertEquals("DA", da.getAbbreviation());
    }

    @Test
    public void toVoHonoursActiveFalse() {
        Source source = newSource();
        source.setActive(false);

        SourceVo vo = mapper.toVo(source, DataFilter.get().fields(DataFilter.ALL));

        assertFalse(vo.isActive());
    }

    @Test
    public void toVoFiltersDescsByLanguage() {
        SourceVo vo = mapper.toVo(newSource(), DataFilter.get().lang("da"));

        // Only the Danish description should survive the language filter
        assertEquals(1, vo.getDescs().size());
        SourceDescVo da = vo.getDescs().get(0);
        assertEquals("da", da.getLang());
        assertEquals("Dansk navn", da.getName());
        assertEquals("DA", da.getAbbreviation());
    }

    @Test
    public void toVoFallsBackToFirstDescWhenLanguageMissing() {
        // No matching language -> the first available description is returned
        SourceVo vo = mapper.toVo(newSource(), DataFilter.get().lang("fr"));

        assertEquals(1, vo.getDescs().size());
        assertEquals("en", vo.getDescs().get(0).getLang());
    }

    @Test
    public void toVoLeavesDescsNullWhenEntityHasNoDescs() {
        Source source = new Source();
        source.setId(7);
        source.setActive(true);

        SourceVo vo = mapper.toVo(source, DataFilter.get().fields(DataFilter.ALL));

        assertEquals(Integer.valueOf(7), vo.getId());
        assertTrue(vo.isActive());
        // toVo only sets descs when the entity has at least one description
        assertNull(vo.getDescs());
    }

    @Test
    public void toVoReturnsNullForNullEntity() {
        assertNull(mapper.toVo(null, DataFilter.get().fields(DataFilter.ALL)));
    }

    @Test
    public void toEntityCopiesBaseFieldsAndDefinedDescs() {
        SourceVo vo = mapper.toVo(newSource(), DataFilter.get().fields(DataFilter.ALL));

        Source entity = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(42), entity.getId());
        assertTrue(entity.isActive());

        assertEquals(2, entity.getDescs().size());

        SourceDesc en = entity.getDescs().get(0);
        assertEquals("en", en.getLang());
        assertEquals("English name", en.getName());
        assertEquals("EN", en.getAbbreviation());
        // addDesc wires the back-reference to the owning entity
        assertEquals(entity, en.getEntity());

        SourceDesc da = entity.getDescs().get(1);
        assertEquals("da", da.getLang());
        assertEquals("Dansk navn", da.getName());
        assertEquals("DA", da.getAbbreviation());
    }

    @Test
    public void toEntitySkipsUndefinedDescs() {
        SourceVo vo = new SourceVo();
        vo.setId(5);
        vo.setActive(true);

        SourceDescVo defined = new SourceDescVo();
        defined.setLang("en");
        defined.setName("Has a name");

        SourceDescVo blank = new SourceDescVo();
        blank.setLang("da");
        // neither name nor abbreviation -> descDefined() == false

        vo.setDescs(List.of(defined, blank));

        Source entity = mapper.toEntity(vo);

        // Only the defined description is carried over
        assertEquals(1, entity.getDescs().size());
        assertEquals("en", entity.getDescs().get(0).getLang());
        assertEquals("Has a name", entity.getDescs().get(0).getName());
    }

    @Test
    public void toEntityReturnsNullForNullVo() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    public void updateEntityCopiesStateAndRemovesBlankDescs() {
        Source target = newSource();
        target.setActive(true);

        // The update source flips active and provides one real + one blank desc
        Source update = new Source();
        update.setActive(false);

        SourceDesc en = update.createDesc("en");
        en.setName("Updated name");
        en.setAbbreviation("UP");

        SourceDesc blank = update.createDesc("da");
        // left blank -> should be removed after copy

        mapper.updateFrom(target, update);

        assertFalse(target.isActive());
        assertEquals(1, target.getDescs().size());
        SourceDesc copied = target.getDescs().get(0);
        assertEquals("en", copied.getLang());
        assertEquals("Updated name", copied.getName());
        assertEquals("UP", copied.getAbbreviation());
    }
}
