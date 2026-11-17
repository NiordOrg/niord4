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

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainMapper;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.report.vo.FmReportVo;
import org.niord.model.DataFilter;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FmReportMapper}: pins the scalar projection, the fixed properties / user
 * params maps and the {@code domains} field gating that was previously embedded in
 * {@code FmReport.toVo(DataFilter)} / {@code FmReport.updateReport(FmReportVo)}, so the extraction is
 * provably behaviour-preserving. Runs as a plain unit test with no persistence context; the nested
 * {@link DomainMapper} is wired by hand. The test domains carry no areas/categories/message-series,
 * so the DomainMapper's own (un-set) collaborators are never dereferenced.
 */
public class FmReportMapperTest {

    private final FmReportMapper mapper = new FmReportMapper(new DomainMapper());

    private static FmReport report() {
        FmReport r = new FmReport();
        r.setReportId("standard");
        r.setSortOrder(5);
        r.setName("Standard report");
        r.setTemplatePath("/templates/standard.ftl");
        r.getProperties().put("pageSize", "A4");
        r.getParams().put("includeAtons", Boolean.TRUE);

        Domain d = new Domain();
        d.setDomainId("dma-nw");
        d.setName("Danish NW");
        d.setActive(true);
        r.setDomains(Arrays.asList(d));
        return r;
    }

    @Test
    public void copiesScalarFieldsAndMaps() {
        // A bare DataFilter.get() includes no named fields; scalar fields are copied unconditionally
        FmReportVo vo = mapper.toVo(report());

        assertEquals("standard", vo.getReportId());
        assertEquals(Integer.valueOf(5), vo.getSortOrder());
        assertEquals("Standard report", vo.getName());
        assertEquals("/templates/standard.ftl", vo.getTemplatePath());
        assertEquals("A4", vo.getProperties().get("pageSize"));
        assertEquals(Boolean.TRUE, vo.getParams().get("includeAtons"));
    }

    @Test
    public void includesDomainsWhenFieldRequested() {
        FmReportVo vo = mapper.toVo(report(), DataFilter.get().fields("domains"));

        assertEquals(1, vo.getDomains().size());
        DomainVo domainVo = vo.getDomains().get(0);
        assertEquals("dma-nw", domainVo.getDomainId());
        assertEquals("Danish NW", domainVo.getName());
        assertEquals(true, domainVo.isActive());
    }

    @Test
    public void omitsDomainsWhenFieldNotRequested() {
        // A filter that lists a different field -> "domains" not included -> list left null
        FmReportVo vo = mapper.toVo(report(), DataFilter.get().fields("name"));

        assertNull(vo.getDomains());
        // ...but the scalar fields are still copied unconditionally
        assertEquals("standard", vo.getReportId());
    }

    @Test
    public void toEntityRoundTrip() {
        // include "domains" so the round-trip carries the nested list through
        FmReportVo vo = mapper.toVo(report(), DataFilter.get().fields(DataFilter.ALL));

        FmReport entity = mapper.toEntity(vo);

        assertEquals("standard", entity.getReportId());
        assertEquals(Integer.valueOf(5), entity.getSortOrder());
        assertEquals("Standard report", entity.getName());
        assertEquals("/templates/standard.ftl", entity.getTemplatePath());
        assertEquals("A4", entity.getProperties().get("pageSize"));
        assertEquals(Boolean.TRUE, entity.getParams().get("includeAtons"));
        assertEquals(1, entity.getDomains().size());
        assertEquals("dma-nw", entity.getDomains().get(0).getDomainId());
        assertEquals("Danish NW", entity.getDomains().get(0).getName());
    }

    @Test
    public void updateEntityWithNullDomainsLeavesDomainsUntouched() {
        FmReportVo vo = new FmReportVo();
        vo.setReportId("r2");
        vo.setName("R2");
        vo.setTemplatePath("/t.ftl");
        // vo.getDomains() is null -> updateEntity must not touch the existing domains list

        FmReport entity = report();
        int domainsBefore = entity.getDomains().size();
        assertTrue(domainsBefore > 0);

        mapper.updateEntity(entity, vo);

        assertEquals("r2", entity.getReportId());
        assertEquals("R2", entity.getName());
        // null domains on the VO => list left as-is
        assertEquals(domainsBefore, entity.getDomains().size());
    }

    @Test
    public void nullEntityMapsToNullVo() {
        assertNull(mapper.toVo(null));
        assertNull(mapper.toVo(null, DataFilter.get()));
    }

    @Test
    public void nullVoMapsToNullEntity() {
        assertNull(mapper.toEntity(null));
    }
}
