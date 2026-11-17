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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credit a PDF gives the map images it shows, and the address that tells a map image apart.
 */
class MapImageCreditTest {

    @Test
    void theCreditIsOpenStreetMapsOwnLineForTheMapImageAddress() {
        MapImageCredit credit = new MapImageCredit();

        assertEquals("© OpenStreetMap contributors", credit.text());
        assertEquals("/rest/message-map-image/", credit.imageAddress());
    }

    @Test
    void theShippedTemplatesShowTheirMapThumbnailsFromTheCreditedAddress() throws Exception {
        // A template that named the image by another address would print it without the credit. The base
        // product's templates are bootstrap data, read by path (in no jar since C4.2a)
        Path file = Path.of("bootstrap/templates/messages/message-support.ftl");
        assertTrue(Files.isRegularFile(file), file.toAbsolutePath() + " is the bootstrap's message-support.ftl");
        String template = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(template.contains("<img src=\"" + MapImageCredit.IMAGE_ADDRESS + "${msg.id}.png\""),
                "the map thumbnail is an image from " + MapImageCredit.IMAGE_ADDRESS);
    }
}
