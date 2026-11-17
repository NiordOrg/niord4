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
import org.niord.core.category.vo.TemplateParamDescVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the {@link TemplateParamDesc} localized-description
 * entity. These mirror the {@code CategoryDesc} test ideas for this class.
 *
 * <p>{@code TemplateParamDesc} is a pure in-memory POJO: every behavior exercised here
 * (the VO constructor, {@link TemplateParamDesc#toVo()}, {@link TemplateParamDesc#descDefined()},
 * {@link TemplateParamDesc#copyDesc} and the getters/setters inherited from
 * {@code DescEntity}) is plain object state with no database, CDI or Quarkus involvement.
 *
 * <p>VO-level assertions for {@link TemplateParamDescVo} are deliberately kept out of this
 * file; this class stays focused on the entity.
 */
public class TemplateParamDescTest {

    /* ===================================================================== */
    /* CONSTRUCTOR FROM VO                                                   */
    /* ===================================================================== */

    /** 1. The TemplateParamDescVo constructor copies both lang and name from the VO. */
    @Test
    public void constructorFromVo_copiesLangAndName() {
        TemplateParamDescVo vo = new TemplateParamDescVo();
        vo.setLang("en");
        vo.setName("Position");

        TemplateParamDesc entity = new TemplateParamDesc(vo);

        assertEquals("en", entity.getLang(), "lang must be copied from the VO");
        assertEquals("Position", entity.getName(), "name must be copied from the VO");
    }

    /** 1b. The VO constructor tolerates a null name (e.g. an undefined description). */
    @Test
    public void constructorFromVo_copiesNullName() {
        TemplateParamDescVo vo = new TemplateParamDescVo();
        vo.setLang("da");
        // name left null

        TemplateParamDesc entity = new TemplateParamDesc(vo);

        assertEquals("da", entity.getLang());
        assertNull(entity.getName(), "a null VO name must remain null on the entity");
    }

    /* ===================================================================== */
    /* toVo()                                                               */
    /* ===================================================================== */

    /** 2. toVo() returns a TemplateParamDescVo carrying the same lang and name. */
    @Test
    public void toVo_carriesLangAndName() {
        TemplateParamDesc entity = new TemplateParamDesc();
        entity.setLang("da");
        entity.setName("Navn");

        TemplateParamDescVo vo = entity.toVo();

        assertEquals("da", vo.getLang());
        assertEquals("Navn", vo.getName());
    }

    /** 2b. toVo() then the VO constructor round-trips lang and name unchanged. */
    @Test
    public void toVo_roundTripsThroughVoConstructor() {
        TemplateParamDesc original = new TemplateParamDesc();
        original.setLang("fr");
        original.setName("Nom");

        TemplateParamDesc back = new TemplateParamDesc(original.toVo());

        assertEquals("fr", back.getLang());
        assertEquals("Nom", back.getName());
    }

    /* ===================================================================== */
    /* descDefined()                                                         */
    /* ===================================================================== */

    /** 3a. descDefined() is true when the name is a non-blank string. */
    @Test
    public void descDefined_trueForNonBlankName() {
        TemplateParamDesc entity = new TemplateParamDesc();
        entity.setName("Position");

        assertTrue(entity.descDefined(), "a non-blank name makes the description defined");
    }

    /** 3b. descDefined() is false when the name is null. */
    @Test
    public void descDefined_falseForNullName() {
        TemplateParamDesc entity = new TemplateParamDesc();
        // name left null

        assertFalse(entity.descDefined(), "a null name leaves the description undefined");
    }

    /** 3c. descDefined() is false when the name is the empty string. */
    @Test
    public void descDefined_falseForEmptyName() {
        TemplateParamDesc entity = new TemplateParamDesc();
        entity.setName("");

        assertFalse(entity.descDefined(), "an empty name leaves the description undefined");
    }

    /* ===================================================================== */
    /* copyDesc()                                                            */
    /* ===================================================================== */

    /** 4. copyDesc() copies the name from another TemplateParamDesc. */
    @Test
    public void copyDesc_copiesNameFromSource() {
        TemplateParamDesc source = new TemplateParamDesc();
        source.setName("Copied name");

        TemplateParamDesc target = new TemplateParamDesc();
        target.setName("Original name");

        target.copyDesc(source);

        assertEquals("Copied name", target.getName(), "name must be copied from the source desc");
    }

    /** 4b. copyDesc() copies only the name and does not touch the lang field. */
    @Test
    public void copyDesc_doesNotChangeLang() {
        TemplateParamDesc source = new TemplateParamDesc();
        source.setLang("en");
        source.setName("English name");

        TemplateParamDesc target = new TemplateParamDesc();
        target.setLang("da");
        target.setName("Dansk navn");

        target.copyDesc(source);

        assertEquals("English name", target.getName(), "name must be overwritten from the source");
        assertEquals("da", target.getLang(), "lang must be left untouched by copyDesc");
    }

    /** 4c. copyDesc() can copy a null name, clearing the previous value. */
    @Test
    public void copyDesc_copiesNullName() {
        TemplateParamDesc source = new TemplateParamDesc();
        // source name left null

        TemplateParamDesc target = new TemplateParamDesc();
        target.setName("Will be cleared");

        target.copyDesc(source);

        assertNull(target.getName(), "copying a null name must clear the target name");
    }

    /* ===================================================================== */
    /* GETTERS / SETTERS ROUND-TRIP                                          */
    /* ===================================================================== */

    /** 5a. getName()/setName() round-trip the name value. */
    @Test
    public void nameGetterSetter_roundTrips() {
        TemplateParamDesc entity = new TemplateParamDesc();

        entity.setName("Some name");

        assertEquals("Some name", entity.getName());
    }

    /** 5b. getLang()/setLang() (inherited from DescEntity) round-trip the lang value. */
    @Test
    public void langGetterSetter_roundTrips() {
        TemplateParamDesc entity = new TemplateParamDesc();

        entity.setLang("de");

        assertEquals("de", entity.getLang());
    }

    /** 5c. getEntity()/setEntity() round-trip the owning TemplateParam. */
    @Test
    public void entityGetterSetter_roundTrips() {
        TemplateParam owner = new TemplateParam();
        owner.setParamId("p1");

        TemplateParamDesc desc = new TemplateParamDesc();
        desc.setEntity(owner);

        assertSame(owner, desc.getEntity(),
                "getEntity() must return the same TemplateParam that was set");
    }

    /** 5d. A freshly constructed desc has no owning entity. */
    @Test
    public void entity_defaultsToNull() {
        TemplateParamDesc desc = new TemplateParamDesc();

        assertNull(desc.getEntity(), "a newly constructed desc has no owning entity");
    }
}
