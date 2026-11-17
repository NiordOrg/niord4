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
package org.niord.core.domain;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.AreaMapper;
import org.niord.core.category.Category;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.message.MessageSeriesMapper;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaVo;
import org.niord.model.message.CategoryVo;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.springframework.stereotype.Component;

import jakarta.inject.Inject;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Domain} entity and {@link DomainVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateDomain()} methods previously embedded in the entity.
 * Nested areas and message-series are delegated to their own mappers. Nested categories are mapped via
 * the {@link Category} entity's own {@code toVo}/constructor (a shallow {@code CategoryVo} projection):
 * this avoids depending on {@code CategoryMapper}, which in turn maps a category's domains — a
 * dependency that would otherwise form a {@code Domain <-> Category} bean cycle.
 */
@Component
public class DomainMapper {

    @Inject
    AreaMapper areaMapper;

    @Inject
    MessageSeriesMapper messageSeriesMapper;

    /** Converts the entity to a value object. */
    public DomainVo toVo(Domain domain) {
        if (domain == null) {
            return null;
        }

        DomainVo vo = new DomainVo();
        vo.setDomainId(domain.getDomainId());
        vo.setActive(domain.isActive());
        vo.setSortOrder(domain.getSortOrder());
        vo.setName(domain.getName());
        vo.setTimeZone(domain.getTimeZone());
        vo.setLat(domain.getLatitude());
        vo.setLon(domain.getLongitude());
        vo.setZoomLevel(domain.getZoomLevel());
        vo.setMessageSortOrder(domain.getMessageSortOrder());
        vo.setPublish(domain.getPublish());
        vo.setAtons(domain.getAtons());
        vo.setTemplates(domain.getTemplates());
        vo.setFiringSchedule(domain.getFiringSchedule() != null);
        vo.setColor(domain.getColor());
        vo.setInKeycloak(domain.getInKeycloak());

        if (!domain.getAreas().isEmpty()) {
            vo.setAreas(domain.getAreas().stream()
                    .map(a -> areaMapper.toVo(a, AreaVo.class, DataFilter.get()))
                    .collect(Collectors.toList()));
        }

        if (!domain.getCategories().isEmpty()) {
            vo.setCategories(domain.getCategories().stream()
                    .map(c -> c.toVo(CategoryVo.class, DataFilter.get()))
                    .collect(Collectors.toList()));
        }

        if (!domain.getMessageSeries().isEmpty()) {
            vo.setMessageSeries(domain.getMessageSeries().stream()
                    .map(ms -> messageSeriesMapper.toVo(ms, SystemMessageSeriesVo.class))
                    .collect(Collectors.toList()));
        }

        return vo;
    }

    /** Creates a new {@link Domain} entity from the given value object. */
    public Domain toEntity(DomainVo vo) {
        if (vo == null) {
            return null;
        }
        Domain domain = new Domain();
        updateEntity(domain, vo);
        return domain;
    }

    /** Updates an existing {@link Domain} entity from the given value object. */
    public void updateEntity(Domain domain, DomainVo vo) {
        domain.setDomainId(vo.getDomainId());
        domain.setActive(vo.isActive());
        domain.setSortOrder(vo.getSortOrder());
        domain.setName(vo.getName());
        domain.setTimeZone(vo.getTimeZone());
        domain.setLatitude(vo.getLat());
        domain.setLongitude(vo.getLon());
        domain.setZoomLevel(vo.getZoomLevel());
        domain.setMessageSortOrder(StringUtils.isBlank(vo.getMessageSortOrder()) ? null : vo.getMessageSortOrder());
        domain.setColor(vo.getColor());
        domain.setPublish(vo.getPublish());
        domain.setAtons(vo.getAtons());
        domain.setTemplates(vo.getTemplates());
        domain.setInKeycloak(vo.getInKeycloak());

        domain.getAreas().clear();
        if (vo.getAreas() != null) {
            domain.setAreas(vo.getAreas().stream()
                    .map(a -> areaMapper.toEntity(a, DataFilter.get()))
                    .collect(Collectors.toList()));
        }

        domain.getCategories().clear();
        if (vo.getCategories() != null) {
            domain.setCategories(vo.getCategories().stream()
                    .map(c -> new Category(c, DataFilter.get()))
                    .collect(Collectors.toList()));
        }

        domain.getMessageSeries().clear();
        if (vo.getMessageSeries() != null) {
            domain.setMessageSeries(vo.getMessageSeries().stream()
                    .map(messageSeriesMapper::toEntity)
                    .collect(Collectors.toList()));
        }
    }
}
