/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.MessageService;
import org.niord.core.report.FmReport;
import org.niord.core.report.FmReportService;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.FmTemplateService.FmTemplateBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The zone a publication issue's document is dated in: the series' zone, which the release hands
 * the renderer in the report parameters, is applied to the rendering itself, so every date the
 * template prints (the cover date, each notice's publication date) is in that zone and not in the
 * JVM's, whatever template row the deployment draws.
 *
 * <p>The template engine is replaced by a builder that records what it was handed; that the zone
 * given to {@link FmTemplateBuilder#timeZone(String)} reaches the printed date is covered in
 * {@code FmTemplateBuilderTimeZoneTest}.
 */
class IssueRenderServiceTimeZoneTest {

    private FmTemplateBuilder builder;
    private IssueRenderService service;

    @BeforeEach
    void setUp() {
        FmTemplateService templateService = mock(FmTemplateService.class);
        builder = mock(FmTemplateBuilder.class, RETURNS_SELF);
        when(templateService.newFmTemplateBuilder()).thenReturn(builder);

        FmReport report = new FmReport();
        report.setReportId("nm-report");
        report.setTemplatePath("/templates/messages/nm-report-pdf.ftl");
        FmReportService fmReportService = mock(FmReportService.class);
        when(fmReportService.findByReportId(any())).thenReturn(report);

        service = new IssueRenderService();
        service.templateService = templateService;
        service.fmReportService = fmReportService;
        service.messageService = mock(MessageService.class);
        service.app = mock(NiordApp.class);
    }

    private static IssueRenderService.RenderRequest request(Map<String, Object> reportParams) {
        return new IssueRenderService.RenderRequest(
                "nm-report", "da", List.of(), null, null, null, false, "", reportParams, null, Set.of());
    }

    @Test
    void theSeriesZoneIsAppliedToTheRendering() {
        service.render(request(Map.of("timeZone", "Europe/Copenhagen")));

        verify(builder).timeZone("Europe/Copenhagen");
    }

    @Test
    void withoutAZoneNothingIsApplied() {
        service.render(request(Map.of("week", "12")));
        service.render(request(null));

        verify(builder, never()).timeZone(anyString());
    }
}
