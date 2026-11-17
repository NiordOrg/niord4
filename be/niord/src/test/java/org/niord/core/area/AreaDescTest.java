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
import org.niord.model.message.AreaDescVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AreaDesc}: the localized-description contract ({@code descDefined},
 * {@code copyDesc}, {@code toVo}) and the VO constructor.
 */
public class AreaDescTest {

    @Test
    public void descNotDefinedWhenNameNull() {
        assertFalse(new AreaDesc().descDefined());
    }

    @Test
    public void descNotDefinedWhenNameEmpty() {
        AreaDesc desc = new AreaDesc();
        desc.setName("");
        assertFalse(desc.descDefined());
    }

    @Test
    public void descDefinedWhenNameSet() {
        AreaDesc desc = new AreaDesc();
        desc.setName("Danmark");
        assertTrue(desc.descDefined());
    }

    @Test
    public void copyDescCopiesName() {
        AreaDesc source = new AreaDesc();
        source.setName("Danmark");

        AreaDesc target = new AreaDesc();
        target.copyDesc(source);

        assertEquals("Danmark", target.getName());
    }

    @Test
    public void toVoMapsLangAndName() {
        AreaDesc desc = new AreaDesc();
        desc.setLang("en");
        desc.setName("Denmark");

        AreaDescVo vo = desc.toVo();

        assertEquals("en", vo.getLang());
        assertEquals("Denmark", vo.getName());
    }

    @Test
    public void constructorFromVoCopiesLangAndName() {
        AreaDesc desc = new AreaDesc(AreaTestFactory.descVo("en", "Denmark"));

        assertEquals("en", desc.getLang());
        assertEquals("Denmark", desc.getName());
    }
}
