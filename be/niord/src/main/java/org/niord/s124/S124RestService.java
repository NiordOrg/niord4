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
package org.niord.s124;

import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.transaction.annotation.Transactional;

import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124Utils;
import jakarta.annotation.security.PermitAll;

import java.io.InputStream;

/**
 * A public REST API for accessing messages as S-124 GML. Only publicly visible messages
 * (published, cancelled or expired) are served — unpublished drafts yield a 404.
 * A message whose GML cannot be generated yields a 400 with a fixed plain-text body; the
 * cause is logged. Both error answers are {@code text/plain}.
 * <p>
 * Mapped only while {@code niord.s124.enabled} is on (the default; {@link S124SwitchCheck}): off,
 * nothing under {@code /rest/S-124} exists, the XSDs included, and every request there answers 404.
 * <p>
 * You can test that the produced GML is valid according to the schema, using something along the lines of:
 * <pre>
 *     xmllint --noout --schema http://localhost:8080/rest/S-124/xsds/124_2.0.0 http://localhost:8080/rest/S-124/messages/NW-061-17
 * </pre>
 */
@RestController
@RequestMapping("/rest/S-124")
@ConditionalOnProperty(name = S124SwitchCheck.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
@Transactional
@PermitAll
public class S124RestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(S124RestService.class);

    /** The type of every error answer: plain text, never markup */
    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final S124Service s124Service;

    S124RestService(S124Service s124Service) {
        this.s124Service = s124Service;
    }

    /**
     * Returns the S-124 GML representation for the given message
     */
    @GetMapping(value = "/messages/{messageId}", produces = "application/gml+xml;charset=UTF-8")
    @Operation(description = "Returns S-124 GML representation for the message. " +
            "The message may be identified by its UID or its short ID (name of series, sequence number and year).")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = String.class)
            )
    )
    public ResponseEntity<String> s124MessageDetails(
            @Parameter(description = "The message UID or short ID", example = "NW-061-17")
            @PathVariable("messageId") String messageId,
            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", defaultValue = "en") String language

    ) throws Exception {

        long t0 = System.currentTimeMillis();

        try {
            String result = s124Service.generateGML(messageId, language);

            // Pretty print the result
            result = S124Utils.prettyPrint(result);

            log.info("Generated GML for message " + messageId + " in " + (System.currentTimeMillis() - t0) + " ms");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/gml+xml;charset=UTF-8"))
                    .body(result);

        } catch (IllegalArgumentException e) {
            log.error("Message does not exist: " + messageId);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .contentType(TEXT_PLAIN_UTF8)
                    .body("No message found with ID: " + messageId);
        } catch (Exception e) {
            // A generation failure can quote message data (the schema validator names the offending value),
            // so the detail goes to the log only and the caller gets a fixed text
            log.error("Error generating S-124 GML for message " + messageId + ": " + e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(TEXT_PLAIN_UTF8)
                    .body("Error generating GML");
        }
    }

    /**
     * Returns the S-124/S-100 XSD schema files bundled with the S-124 bindings library
     */
    @GetMapping(value = "/xsds/{file}", produces = "text/xml;charset=UTF-8")
    public ResponseEntity<String> xsdFile(
            @PathVariable("file") String file

    ) throws Exception {

        String xsdFile = file.endsWith(".xsd") ? file : file + ".xsd";

        try (InputStream in = getClass().getResourceAsStream("/xsd/" + xsdFile)) {

            String xsd = IOUtils.toString(in);

            log.info("Returning XSD " + xsdFile);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/xml;charset=UTF-8"))
                    .body(xsd);

        } catch (Exception e) {
            log.error("XSD does not exist: " + xsdFile);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body("XSD does not exist: " + xsdFile);
        }
    }

}
