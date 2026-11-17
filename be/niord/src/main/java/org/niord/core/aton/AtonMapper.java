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

import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Maps between the {@link AtonNode} entity and {@link AtonNodeVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code AtonNode(AtonNodeVo)} conversion constructor
 * previously embedded in the entity. The owned {@link AtonTag} children are mapped inline.
 * {@code AtonNodeVo} is single-tier (OSM seamark node).
 */
@Component
public class AtonMapper {

    /** Converts the entity to a value object. */
    public AtonNodeVo toVo(AtonNode node) {
        if (node == null) {
            return null;
        }
        AtonNodeVo vo = new AtonNodeVo();
        vo.setId(node.getId());
        vo.setLat(node.getLat());
        vo.setLon(node.getLon());
        vo.setUser(node.getUser());
        vo.setUid(node.getUid());
        vo.setVisible(node.isVisible());
        vo.setVersion(node.getVersion());
        vo.setChangeset(node.getChangeset());
        vo.setTimestamp(node.getTimestamp());
        vo.setTags(node.getTags().stream()
                .map(this::toTagVo)
                .toArray(AtonTagVo[]::new));
        return vo;
    }

    /** Maps a single {@link AtonTag} to its value object. */
    private AtonTagVo toTagVo(AtonTag tag) {
        return new AtonTagVo(tag.getK(), tag.getV());
    }

    /** Creates a new {@link AtonNode} entity from the given value object. */
    public AtonNode toEntity(AtonNodeVo vo) {
        if (vo == null) {
            return null;
        }
        AtonNode node = new AtonNode();
        node.setId(vo.getId());
        node.setLat(vo.getLat());
        node.setLon(vo.getLon());
        node.setUser(vo.getUser());
        node.setUid(vo.getUid());
        node.setVisible(vo.isVisible());
        node.setVersion(vo.getVersion());
        node.setChangeset(vo.getChangeset());
        node.setTimestamp(vo.getTimestamp());
        if (vo.getTags() != null) {
            node.setTags(Arrays.stream(vo.getTags())
                    .map(t -> new AtonTag(t, node))
                    .collect(Collectors.toList()));
        }
        return node;
    }
}
