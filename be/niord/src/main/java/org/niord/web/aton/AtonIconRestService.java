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
package org.niord.web.aton;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.aton.AtonNode;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.slf4j.Logger;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Renders AtoN icons.
 */
@RestController
@RequestMapping("/rest/aton-icon")
@PermitAll
public class AtonIconRestService {

    static final int OVERVIEW_ICON_HEIGHT = 90;
    static final int OVERVIEW_ICON_WIDTH = 60;
    static final double OVERVIEW_ICON_SCALE = 0.3;
    static final int OVERVIEW_ICON_TTL_HOURS = 24;

    /** The largest width and height, in pixels, an SVG icon is rendered with */
    static final int MAX_SVG_SIZE = 1000;
    /** The largest symbol scale an SVG icon is rendered with */
    static final double MAX_SVG_SCALE = 10.0;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AtonIconRestService.class);

    /**
     * Renders the posted AtoN as an SVG document.
     *
     * The width and height must lie within 1 to {@link #MAX_SVG_SIZE} pixels and the scale within
     * (0, {@link #MAX_SVG_SCALE}]; anything else is refused with 400 before the icon is rendered. An AtoN without
     * tags is refused with 400 as well.
     */
    @PostMapping(value = "/svg", consumes = "application/json;charset=UTF-8", produces = "image/svg+xml")
    public ResponseEntity<byte[]> createSvgForAton(
            @RequestParam(name = "width", defaultValue = "100") int width,
            @RequestParam(name = "height", defaultValue = "100") int height,
            @RequestParam(name = "scale", defaultValue = "0.4") double scale,
            @RequestBody AtonNodeVo aton) throws Exception {

        if (width < 1 || width > MAX_SVG_SIZE || height < 1 || height > MAX_SVG_SIZE
                || !(scale > 0 && scale <= MAX_SVG_SCALE)) {
            throw new NiordWebException(400);
        }
        // The icon is drawn from the AtoN's tags (AtonIconRenderer)
        if (aton == null || aton.getTags() == null) {
            throw new NiordWebException("No AtoN tags to render", 400);
        }

        long t0 = System.currentTimeMillis();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtonIconRenderer.renderIcon(
                aton,
                "svg",
                out,
                width,       // width
                height,      // height
                width/2,     // x
                height/2,    // y
                scale        // scale
        );

        log.trace("Generated AtoN SVG in " + (System.currentTimeMillis() - t0) + " ms");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(out.toByteArray());
    }

    /**
     * Renders the overview icon of the AtoN the seamark parameters describe, as a 60x90 PNG.
     *
     * The icon is rendered in memory and answered directly, with a Cache-Control lifetime; nothing is
     * written to the repository. The icons used to be cached there, one file for each combination of
     * parameter values a caller chose, so the files an anonymous caller could create had no bound.
     * Without a {@code seamark:type} the answer is a redirect to the static placeholder icon.
     */
    @GetMapping("/overview")
    public ResponseEntity<byte[]> getAtonOverviewIcon(HttpServletRequest request) throws Exception {

        long t0 = System.currentTimeMillis();

        String type = request.getParameter("seamark:type");
        if (StringUtils.isBlank(type)) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(WebUtils.absoluteUrl("/img/aton/aton.png"))
                    .build();
        }

        // Prepare an AtoN to use as a template for icon construction
        AtonNode aton = new AtonNode();

        addParam(aton, request, "seamark:type");
        addParam(aton, request, "seamark:" + type + ":category");
        addParam(aton, request, "seamark:" + type + ":shape");
        addParam(aton, request, "seamark:" + type + ":colour");
        addParam(aton, request, "seamark:" + type + ":colour_pattern");
        addParam(aton, request, "seamark:topmark:shape");
        addParam(aton, request, "seamark:topmark:colour");
        addParam(aton, request, "seamark:light:character");
        addParam(aton, request, "seamark:light:colour");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtonIconRenderer.renderIcon(
                aton.toVo(),
                "png",
                out,
                OVERVIEW_ICON_WIDTH,            // width
                OVERVIEW_ICON_HEIGHT,           // height
                OVERVIEW_ICON_WIDTH / 3,        // x
                2 * OVERVIEW_ICON_HEIGHT / 3,   // y
                OVERVIEW_ICON_SCALE             // scale
        );

        log.trace("Generated AtoN PNG in " + (System.currentTimeMillis() - t0) + " ms");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(OVERVIEW_ICON_TTL_HOURS, TimeUnit.HOURS))
                .contentType(MediaType.IMAGE_PNG)
                .body(out.toByteArray());
    }

    /**
     * If the parameter is well-defined, add it as a tag to the AtoN template
     *
     * @param aton an AtoN template to update with the parameter tags
     * @param request the servlet request
     * @param param the param to check for
     */
    private void addParam(AtonNode aton, HttpServletRequest request, String param) {
        String val = request.getParameter(param);
        if (StringUtils.isNotBlank(val)) {
            aton.updateTag(param, val);
        }
    }

}
