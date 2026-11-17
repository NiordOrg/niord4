/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.web.map;

import org.springframework.stereotype.Component;

/**
 * The credit a PDF gives the map images it shows.
 * <p>
 * The map images served under {@link MessageMapImageRestService#PATH} are drawn on OpenStreetMap tiles,
 * and OpenStreetMap's licence asks for a visible credit wherever its map is shown. A custom thumbnail is
 * served from the same address and usually shows map tiles as well, so it counts as a map image too.
 * <p>
 * A PDF that contains such an image carries the credit in its page footer. The PDF renderer adds it
 * (see {@code HtmlToPdfRenderer.HtmlToPdfRendererBuilder#mapCredit}), not a template, because the template
 * a deployment renders is often the copy kept in its database, which an edit of the class-path file never
 * reaches; so every template gets it, the country ones included, and a PDF without a map image gets none.
 * <p>
 * The credit lives here, beside the endpoint that serves the images, so a change of what the images are
 * drawn on and of what they credit is one change in one place. The text is OpenStreetMap's own credit line;
 * it reads the same in every language, so it has no dictionary entry.
 */
@Component
public class MapImageCredit {

    /** The address every map image is served from, as it appears in the {@code src} of an image */
    public static final String IMAGE_ADDRESS = MessageMapImageRestService.PATH + "/";

    /** OpenStreetMap's credit line */
    public static final String OPENSTREETMAP_CREDIT = "© OpenStreetMap contributors";

    /** Returns the address every map image is served from */
    public String imageAddress() {
        return IMAGE_ADDRESS;
    }

    /** Returns the credit line a PDF showing a map image carries */
    public String text() {
        return OPENSTREETMAP_CREDIT;
    }
}
