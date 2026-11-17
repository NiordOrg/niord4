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
package org.niord.core.report;

import org.niord.core.domain.DomainMapper;
import org.niord.core.report.vo.FmReportVo;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Maps between the {@link FmReport} entity and {@link FmReportVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateReport()} methods previously embedded in the entity.
 * The nested {@code domains} list is delegated to the {@link DomainMapper}.
 */
@Component
public class FmReportMapper {

    private final DomainMapper domainMapper;

    FmReportMapper(DomainMapper domainMapper) {
        this.domainMapper = domainMapper;
    }

    /** Converts the entity to a value object. */
    public FmReportVo toVo(FmReport report) {
        return toVo(report, DataFilter.get());
    }

    /** Converts the entity to a value object. */
    public FmReportVo toVo(FmReport entity, DataFilter filter) {
        if (entity == null) {
            return null;
        }

        FmReportVo report = new FmReportVo();
        report.setReportId(entity.getReportId());
        report.setSortOrder(entity.getSortOrder());
        report.setName(entity.getName());
        report.setTemplatePath(entity.getTemplatePath());
        report.getProperties().putAll(entity.getProperties());
        report.getParams().putAll(entity.getParams());
        if (filter.includeField("domains")) {
            report.setDomains(entity.getDomains().stream()
                .map(domainMapper::toVo)
                .collect(Collectors.toList()));
        }
        return report;
    }

    /** Creates a new {@link FmReport} entity from the given value object. */
    public FmReport toEntity(FmReportVo vo) {
        if (vo == null) {
            return null;
        }
        FmReport report = new FmReport();
        updateEntity(report, vo);
        return report;
    }

    /** Updates an existing {@link FmReport} entity from the given value object. */
    public void updateEntity(FmReport entity, FmReportVo report) {
        entity.setReportId(report.getReportId());
        entity.setSortOrder(report.getSortOrder());
        entity.setName(report.getName());
        entity.setTemplatePath(report.getTemplatePath());
        entity.getProperties().putAll(report.getProperties());
        entity.getParams().putAll(report.getParams());
        if (report.getDomains() != null) {
            entity.setDomains(report.getDomains().stream()
                    .map(domainMapper::toEntity)
                    .collect(Collectors.toList()));
        }
    }
}
