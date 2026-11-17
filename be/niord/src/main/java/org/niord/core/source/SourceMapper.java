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

import org.niord.core.source.vo.SourceDescVo;
import org.niord.core.source.vo.SourceVo;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Maps {@link Source} entities to and from their value objects.
 *
 * <p>The conversion logic is copied verbatim from the entity's {@code toVo(...)},
 * {@code updateSource(...)} and conversion constructor(s), so that this mapper is a
 * behaviour-preserving extraction of the existing in-entity conversion code.
 */
@Component
public class SourceMapper {

    /** Converts the given entity to a value object */
    public SourceVo toVo(Source source, DataFilter dataFilter) {
        if (source == null) {
            return null;
        }
        SourceVo vo = new SourceVo();
        vo.setId(source.getId());
        vo.setActive(source.isActive());

        if (!source.getDescs().isEmpty()) {
            vo.setDescs(source.getDescs(dataFilter).stream()
                    .map(this::toDescVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    /** Converts the given description entity to a value object */
    private SourceDescVo toDescVo(SourceDesc desc) {
        SourceDescVo vo = new SourceDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
        vo.setAbbreviation(desc.getAbbreviation());
        return vo;
    }

    /** Converts the given value object to a new entity */
    public Source toEntity(SourceVo vo) {
        if (vo == null) {
            return null;
        }
        Source source = new Source();
        source.setId(vo.getId());
        source.setActive(vo.isActive());
        if (vo.getDescs() != null) {
            vo.getDescs().stream()
                    .filter(SourceDescVo::descDefined)
                    .forEach(desc -> source.addDesc(toDescEntity(desc)));
        }
        return source;
    }

    /** Converts the given description value object to a new entity */
    private SourceDesc toDescEntity(SourceDescVo desc) {
        SourceDesc entity = new SourceDesc();
        entity.setLang(desc.getLang());
        entity.setName(desc.getName());
        entity.setAbbreviation(desc.getAbbreviation());
        return entity;
    }

    /** Updates the given entity from another source entity (entity-to-entity copy, not a VO update). */
    public void updateFrom(Source source, Source another) {
        source.setActive(another.isActive());
        source.copyDescsAndRemoveBlanks(another.getDescs());
    }

}
