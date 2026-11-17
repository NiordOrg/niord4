/*
 * Copyright 2024 Danish Maritime Authority.
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

package org.niord.core.category.vo;

import org.junit.jupiter.api.Test;
import org.niord.core.category.ParamType;
import org.niord.core.category.StandardParamType;
import org.niord.core.dictionary.vo.DictionaryEntryVo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the small uncovered helper methods of the
 * {@code ParamType} value-object hierarchy ({@link ParamTypeVo}, {@link ListParamTypeVo},
 * {@link CompositeParamTypeVo}, {@link StandardParamTypeVo}, {@link TemplateParamVo},
 * {@link TemplateParamDescVo}) and the edges of the {@link ParamType}/{@link StandardParamType}
 * entities.
 *
 * <p>This class is a deliberate complement to {@code ParamTypeConversionTest} (which covers the
 * entity &lt;-&gt; VO conversions and Jackson polymorphism) and does not duplicate it. The methods
 * exercised here are the lazy "checkCreate" list initializers, the {@code toEntity()}
 * fall-through, the {@code STANDARD_PARAM_TYPES} catalogue, simple constructor/accessor
 * round-trips and the localized-description helpers.
 *
 * <p>All assertions are pure in-memory object state: no database, no CDI, no Quarkus.
 */
public class ParamTypeVoHelpersTest {

    /* ===================================================================== */
    /* checkCreate*() lazy initializers                                      */
    /* ===================================================================== */

    /**
     * 1. ListParamTypeVo.checkCreateValues() lazily replaces a null values list with a fresh
     *    empty list, and a second call returns the very same instance (no re-allocation).
     */
    @Test
    public void listParamTypeVoCheckCreateValues_initializesNullListAndIsIdempotent() {
        ListParamTypeVo vo = new ListParamTypeVo();
        vo.setValues(null);
        assertNull(vo.getValues(), "Pre-condition: values must have been nulled");

        List<DictionaryEntryVo> created = vo.checkCreateValues();

        assertNotNull(created, "checkCreateValues() must create a non-null list");
        assertTrue(created.isEmpty(), "The freshly created list must be empty");
        assertSame(created, vo.getValues(), "getValues() must now expose the created list");

        // Idempotent: calling again returns the SAME list instance, not a new one.
        assertSame(created, vo.checkCreateValues(), "A second call must reuse the existing list");
    }

    /**
     * 2. CompositeParamTypeVo.checkCreateTemplateParams() follows the same lazy pattern: it
     *    replaces a null templateParams list and returns the same instance on subsequent calls.
     */
    @Test
    public void compositeParamTypeVoCheckCreateTemplateParams_initializesNullListAndIsIdempotent() {
        CompositeParamTypeVo vo = new CompositeParamTypeVo();
        vo.setTemplateParams(null);
        assertNull(vo.getTemplateParams(), "Pre-condition: templateParams must have been nulled");

        List<TemplateParamVo> created = vo.checkCreateTemplateParams();

        assertNotNull(created, "checkCreateTemplateParams() must create a non-null list");
        assertTrue(created.isEmpty(), "The freshly created list must be empty");
        assertSame(created, vo.getTemplateParams(), "getTemplateParams() must now expose the created list");

        // Idempotent: calling again returns the SAME list instance, not a new one.
        assertSame(created, vo.checkCreateTemplateParams(), "A second call must reuse the existing list");
    }

    /* ===================================================================== */
    /* ParamTypeVo.toEntity() fall-through                                   */
    /* ===================================================================== */

    /**
     * 3. ParamTypeVo.toEntity() returns null for a VO that is none of the three known subtypes
     *    (List/Composite/Standard). An anonymous subclass exercises the final fall-through branch
     *    where none of the instanceof checks match.
     */
    @Test
    public void paramTypeVoToEntity_returnsNullForUnknownSubtype() {
        ParamTypeVo unknown = new ParamTypeVo() {
            // anonymous subtype that is neither List, Composite nor Standard
        };
        unknown.setName("mystery");

        assertNull(unknown.toEntity(),
                "An unrecognized ParamTypeVo subtype must map to a null entity");
    }

    /* ===================================================================== */
    /* StandardParamType catalogue + entity edges                           */
    /* ===================================================================== */

    /**
     * 4. StandardParamType.STANDARD_PARAM_TYPES lists exactly the 7 expected standard type names,
     *    in the documented order.
     */
    @Test
    public void standardParamTypesCatalogue_containsTheSevenExpectedNames() {
        String[] types = StandardParamType.STANDARD_PARAM_TYPES;

        assertEquals(7, types.length, "Exactly 7 standard parameter types are defined");
        assertEquals("text", types[0]);
        assertEquals("number", types[1]);
        assertEquals("boolean", types[2]);
        assertEquals("date", types[3]);
        assertEquals("aton_name", types[4]);
        assertEquals("light_character", types[5]);
        assertEquals("call_sign", types[6]);
    }

    /**
     * 5. The StandardParamType(String) constructor stores the name and leaves the id null (the
     *    entity is transient until persisted).
     */
    @Test
    public void standardParamTypeNameConstructor_setsNameAndLeavesIdNull() {
        StandardParamType entity = new StandardParamType("light_character");

        assertEquals("light_character", entity.getName());
        assertNull(entity.getId(), "A name-only constructed entity has no generated id yet");
    }

    /**
     * 6. ParamType.getName()/setName() is a faithful round-trip (verified via the concrete
     *    StandardParamType subclass, since ParamType is abstract).
     */
    @Test
    public void paramTypeName_setterGetterRoundTrip() {
        StandardParamType entity = new StandardParamType();
        assertNull(entity.getName(), "A no-arg constructed entity starts with a null name");

        entity.setName("call_sign");
        assertEquals("call_sign", entity.getName());

        entity.setName(null);
        assertNull(entity.getName(), "Setting the name back to null must be honored");
    }

    /* ===================================================================== */
    /* TemplateParamVo / TemplateParamDescVo localized-description helpers   */
    /* ===================================================================== */

    /**
     * 7. TemplateParamVo.createDesc("en") lazily initializes the descs list (checkCreateDescs)
     *    and appends a single TemplateParamDescVo tagged with the requested language.
     */
    @Test
    public void templateParamVoCreateDesc_initializesDescsAndTagsLanguage() {
        TemplateParamVo vo = new TemplateParamVo();
        assertNull(vo.getDescs(), "descs starts null until the first createDesc() call");

        TemplateParamDescVo desc = vo.createDesc("en");

        assertNotNull(desc, "createDesc() must return the created description");
        assertEquals("en", desc.getLang());

        List<TemplateParamDescVo> descs = vo.getDescs();
        assertNotNull(descs, "createDesc() must lazily initialize the descs list");
        assertEquals(1, descs.size(), "Exactly one description must have been added");
        assertSame(desc, descs.get(0), "The returned desc must be the one stored in the list");
    }

    /**
     * 8a. TemplateParamDescVo.descDefined() is true when the name is a non-blank value and
     *     false when the name is null (the entity is considered "empty" then).
     */
    @Test
    public void templateParamDescVoDescDefined_reflectsNamePresence() {
        TemplateParamDescVo defined = new TemplateParamDescVo();
        defined.setName("Position");
        assertTrue(defined.descDefined(), "A non-blank name makes the description defined");

        TemplateParamDescVo undefined = new TemplateParamDescVo();
        undefined.setName(null);
        assertFalse(undefined.descDefined(), "A null name makes the description undefined");
    }

    /**
     * 8b. TemplateParamDescVo.copyDesc() copies the name from the source description.
     */
    @Test
    public void templateParamDescVoCopyDesc_copiesName() {
        TemplateParamDescVo source = new TemplateParamDescVo();
        source.setName("Light character");

        TemplateParamDescVo target = new TemplateParamDescVo();
        target.copyDesc(source);

        assertEquals("Light character", target.getName(), "copyDesc() must copy the source name");
    }

    /**
     * 8c. TemplateParamDescVo.getLang()/setLang() is a faithful round-trip.
     */
    @Test
    public void templateParamDescVoLang_setterGetterRoundTrip() {
        TemplateParamDescVo desc = new TemplateParamDescVo();
        assertNull(desc.getLang(), "lang starts null on a fresh description");

        desc.setLang("da");
        assertEquals("da", desc.getLang());
    }
}
