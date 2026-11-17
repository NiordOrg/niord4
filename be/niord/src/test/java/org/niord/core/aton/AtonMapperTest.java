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
package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for {@link AtonMapper}: scalar + tag-array projection and round-trip. */
public class AtonMapperTest {

    private final AtonMapper mapper = new AtonMapper();

    private static AtonNodeVo nodeVo() {
        AtonNodeVo vo = new AtonNodeVo();
        vo.setId(11);
        vo.setLat(56.5);
        vo.setLon(10.5);
        vo.setUser("importer");
        vo.setUid(7);
        vo.setVisible(true);
        vo.setVersion(2);
        vo.setChangeset(99);
        vo.setTags(new AtonTagVo[]{ new AtonTagVo("seamark:type", "light_major") });
        return vo;
    }

    @Test
    public void toEntityCopiesScalarsAndTags() {
        AtonNode node = mapper.toEntity(nodeVo());

        assertEquals(Integer.valueOf(11), node.getId());
        assertEquals(56.5, node.getLat(), 0.0001);
        assertEquals(10.5, node.getLon(), 0.0001);
        assertEquals("importer", node.getUser());
        assertEquals(7, node.getUid());
        assertEquals(true, node.isVisible());
        assertEquals(2, node.getVersion());
        assertEquals(99, node.getChangeset());
        assertEquals(1, node.getTags().size());
        assertEquals("seamark:type", node.getTags().get(0).getK());
        assertEquals("light_major", node.getTags().get(0).getV());
    }

    @Test
    public void toVoMatchesEntityRoundTrip() {
        AtonNode node = mapper.toEntity(nodeVo());

        AtonNodeVo vo = mapper.toVo(node);
        // differential vs the still-present entity method
        AtonNodeVo entityVo = node.toVo();

        assertEquals(entityVo.getId(), vo.getId());
        assertEquals(entityVo.getLat(), vo.getLat(), 0.0001);
        assertEquals(entityVo.getLon(), vo.getLon(), 0.0001);
        assertEquals(entityVo.getUser(), vo.getUser());
        assertEquals(entityVo.getTags().length, vo.getTags().length);
        assertEquals("seamark:type", vo.getTags()[0].getK());
        assertEquals("light_major", vo.getTags()[0].getV());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
