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

import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaDescVo;
import org.niord.model.message.AreaVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Area} entity and {@link AreaVo}/{@link SystemAreaVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateArea()} methods that were previously embedded in the
 * {@link Area} entity. The recursive parent/children traversal that the entity performed by calling
 * its own {@code toVo} is preserved here as self-recursion on the mapper, so conversion no longer
 * lives on the persistence entity. The two-tier {@code AreaVo}/{@code SystemAreaVo} behaviour and the
 * {@link DataFilter} semantics (incl. language filtering and the first-desc fallback in
 * {@code getDescs(filter)}) are kept identical.
 */
@Component
public class AreaMapper {

    /** Converts the entity to a value object, honouring the data filter. */
    public <A extends AreaVo> A toVo(Area area, Class<A> clz, DataFilter filter) {
        if (area == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(Area.class);

        AreaVo vo = SystemAreaVo.class.isAssignableFrom(clz) ? new SystemAreaVo() : new AreaVo();
        vo.setId(area.getId());
        vo.setMrn(area.getMrn());
        vo.setActive(area.isActive());

        if (vo instanceof SystemAreaVo sysArea) {
            if (compFilter.includeDetails()) {
                sysArea.setType(area.getType());
                sysArea.setSiblingSortOrder(area.getSiblingSortOrder());
                sysArea.setMessageSorting(area.getMessageSorting());
                sysArea.setOriginLatitude(area.getOriginLatitude());
                sysArea.setOriginLongitude(area.getOriginLongitude());
                sysArea.setOriginAngle(area.getOriginAngle());

                if (!area.getEditorFields().isEmpty()) {
                    sysArea.setEditorFields(new ArrayList<>(area.getEditorFields()));
                }
            }

            if (compFilter.includeGeometry()) {
                sysArea.setGeometry(JtsConverter.fromJts(area.getGeometry()));
            }

            if (compFilter.includeChildren()) {
                area.getChildren().forEach(child ->
                        sysArea.checkCreateChildren().add(toVo(child, SystemAreaVo.class, filter)));
            }
        }

        if (compFilter.includeParent() && area.getParent() != null) {
            vo.setParent(toVo(area.getParent(), clz, filter));
        } else if (compFilter.includeParentId() && area.getParent() != null) {
            AreaVo parent = new AreaVo();
            parent.setId(area.getParent().getId());
            vo.setParent(parent);
        }

        if (!area.getDescs().isEmpty()) {
            vo.setDescs(area.getDescs(filter).stream()
                    .map(this::toDescVo)
                    .collect(Collectors.toList()));
        }

        return clz.cast(vo);
    }

    /** Maps a single {@link AreaDesc} to its value object. */
    private AreaDescVo toDescVo(AreaDesc desc) {
        AreaDescVo vo = new AreaDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
        return vo;
    }

    /** Creates a new {@link Area} entity from the given value object. */
    public Area toEntity(AreaVo vo, DataFilter filter) {
        if (vo == null) {
            return null;
        }
        Area area = new Area();
        updateEntity(area, vo, filter);
        return area;
    }

    /** Updates an existing {@link Area} entity from the given value object. */
    public void updateEntity(Area area, AreaVo vo, DataFilter filter) {

        DataFilter compFilter = filter.forComponent(Area.class);

        area.setMrn(vo.getMrn());
        area.setActive(vo.isActive());
        area.setId(vo.getId());

        if (compFilter.includeParent() && vo.getParent() != null) {
            area.setParent(toEntity(vo.getParent(), filter));
        }
        if (vo.getDescs() != null) {
            vo.getDescs().forEach(desc -> area.createDesc(desc.getLang()).setName(desc.getName()));
        }

        if (vo instanceof SystemAreaVo sysArea) {
            area.setType(sysArea.getType());
            area.setSiblingSortOrder(sysArea.getSiblingSortOrder() == null ? 0 : sysArea.getSiblingSortOrder());
            area.setMessageSorting(sysArea.getMessageSorting());
            area.setOriginLatitude(sysArea.getOriginLatitude());
            area.setOriginLongitude(sysArea.getOriginLongitude());
            area.setOriginAngle(sysArea.getOriginAngle());

            if (compFilter.includeGeometry()) {
                // Stored on the globe: moved by whole turns into range, a latitude beyond 90 refused
                area.setGeometry(JtsConverter.toJtsOnGlobe(sysArea.getGeometry()));
            }

            if (compFilter.includeChildren() && sysArea.getChildren() != null) {
                sysArea.getChildren().stream()
                        .map(a -> toEntity(a, filter))
                        .forEach(area::addChild);
            }
            if (sysArea.getEditorFields() != null) {
                area.getEditorFields().addAll(sysArea.getEditorFields());
            }
        }
    }
}
