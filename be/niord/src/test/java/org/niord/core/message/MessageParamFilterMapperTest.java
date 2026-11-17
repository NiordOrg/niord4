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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.MessageParamFilterVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link MessageParamFilterMapper}.
 */
public class MessageParamFilterMapperTest {

    private final MessageParamFilterMapper mapper = new MessageParamFilterMapper();

    @Test
    public void toVoCopiesAllFields() {
        MessageParamFilter entity = new MessageParamFilter();
        entity.setId(42);
        entity.setName("My Filter");
        entity.setParameters("status=PUBLISHED&mainType=NW");

        MessageParamFilterVo vo = mapper.toVo(entity);

        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals("My Filter", vo.getName());
        assertEquals("status=PUBLISHED&mainType=NW", vo.getParameters());
    }

    @Test
    public void toVoPreservesNullFields() {
        MessageParamFilter entity = new MessageParamFilter();
        entity.setName("only-name");
        // id and parameters left null

        MessageParamFilterVo vo = mapper.toVo(entity);

        assertNull(vo.getId());
        assertEquals("only-name", vo.getName());
        assertNull(vo.getParameters());
    }

    @Test
    public void toVoOfNullEntityReturnsNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void toEntityCopiesAllFields() {
        MessageParamFilterVo vo = new MessageParamFilterVo();
        vo.setId(7);
        vo.setName("VO Filter");
        vo.setParameters("type=PERMIT");

        MessageParamFilter entity = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(7), entity.getId());
        assertEquals("VO Filter", entity.getName());
        assertEquals("type=PERMIT", entity.getParameters());
    }

    @Test
    public void toEntityOfNullVoReturnsNull() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    public void updateEntityOverwritesExistingFields() {
        MessageParamFilter entity = new MessageParamFilter();
        entity.setId(1);
        entity.setName("old-name");
        entity.setParameters("old=params");

        MessageParamFilterVo vo = new MessageParamFilterVo();
        vo.setId(99);
        vo.setName("new-name");
        vo.setParameters("new=params");

        mapper.updateEntity(entity, vo);

        assertEquals(Integer.valueOf(99), entity.getId());
        assertEquals("new-name", entity.getName());
        assertEquals("new=params", entity.getParameters());
    }

    @Test
    public void entityToVoToEntityRoundTripPreservesValues() {
        MessageParamFilter original = new MessageParamFilter();
        original.setId(123);
        original.setName("round-trip");
        original.setParameters("a=1&b=2");

        MessageParamFilterVo vo = mapper.toVo(original);
        MessageParamFilter roundTripped = mapper.toEntity(vo);

        assertEquals(original.getId(), roundTripped.getId());
        assertEquals(original.getName(), roundTripped.getName());
        assertEquals(original.getParameters(), roundTripped.getParameters());
    }
}
