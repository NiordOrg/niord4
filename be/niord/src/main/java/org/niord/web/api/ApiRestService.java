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
package org.niord.web.api;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.message.Message;
import org.niord.core.web.NiordWebException;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageVo;
import org.niord.model.publication.PublicationVo;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.security.PermitAll;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.SchemaOutputResolver;

/**
 * A public REST API for accessing message and publications Niord data.
 * <p>
 * Also, defines the Swagger API using annotations.
 * <p>
 * NB: Swagger codegen-generated model classes cannot handle UNIX Epoch timestamps, which is the date format
 * used in JSON throughout Niord. Instead, these classes expects dates to be encoded as ISO-8601 strings
 * (e.g. "2016-10-12T13:21:22.000+0000").<br>
 * To facilitate both formats, use the "dateFormat" parameter.
 */
@RestController
@RequestMapping("/rest/public/v1")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class ApiRestService extends AbstractApiService {

    /** application/json;charset=utf-8 — the exact content type the original JAX-RS responses produced. */
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=utf-8");

    /** application/xml;charset=UTF-8 — the content type the message schemas are served with. */
    private static final MediaType XML_UTF8 = MediaType.parseMediaType("application/xml;charset=UTF-8");

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiRestService.class);

    /**
     * The format to use for dates in generated JSON
     **/
    public enum JsonDateFormat {
        UNIX_EPOCH, ISO_8601
    }

    private final NiordApp app;

    ApiRestService(NiordApp app) {
        this.app = app;
    }

    /***************************
     * Message end-points
     ***************************/

    /**
     * {@inheritDoc}
     */
    @GetMapping(value = "/messages", produces = "application/json;charset=UTF-8")
    @Operation(summary = "Returns the published NW and NM messages")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MessageVo.class, type= SchemaType.ARRAY)
            )
    )
    public ResponseEntity<String> searchMessages(
            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language,

            @Parameter(description = "The IDs of the domains to select messages from", example = "niord-client-nw")
            @RequestParam(name = "domain", required = false) Set<String> domainIds,

            @Parameter(description = "Specific message series to select messages from", example = "dma-nw")
            @RequestParam(name = "messageSeries", required = false) Set<String> messageSeries,

            @Parameter(description = "The IDs of the publications to select message from")
            @RequestParam(name = "publication", required = false) Set<String> publicationIds,

            @Parameter(description = "The IDs of the areas to select messages from", example = "urn:mrn:iho:country:dk")
            @RequestParam(name = "areaId", required = false) Set<String> areaIds,

            @Parameter(description = "Either NW (navigational warnings) or NM (notices to mariners)", example = "NW")
            @RequestParam(name = "mainType", required = false) Set<MainType> mainTypes,

            @Parameter(description = "Well-Known Text for geographical extent", example = "POLYGON((7 54, 7 57, 13 56, 13 57, 7 54))")
            @RequestParam(name = "wkt", required = false) String wkt,

            @Parameter(description = "Whether to rewrite all embedded links and paths to be absolute URL's", example = "true")
            @RequestParam(name = "externalize", defaultValue = "true") boolean externalize,

            @Parameter(description = "The date format to use for JSON date-time encoding. Either 'UNIX_EPOCH' or 'ISO_8601'", example = "UNIX_EPOCH")
            @RequestParam(name = "dateFormat", defaultValue = "UNIX_EPOCH") JsonDateFormat dateFormat

    ) throws Exception {
        log.debug("Messages-Params Request parameters: " +
                "lang={}, domain={}, messageSeries={}, publication={}, " +
                "areaId={}, mainType={}, wkt={}, externalize={}, dateFormat={}",
                language,
                domainIds,
                messageSeries,
                publicationIds,
                areaIds,
                mainTypes,
                wkt,
                externalize,
                dateFormat);

        // Perform the search
        PagedSearchResultVo<Message> searchResult =
                super.searchMessages(language, domainIds, messageSeries, publicationIds, areaIds, mainTypes, wkt);

        // Convert messages to value objects and externalize message links, if requested
        List<MessageVo> messages = searchResult
                .map(m -> toMessageVo(m, language, externalize))
                .getData();

        // Depending on the dateFormat param, either use UNIX epoch or ISO-8601
        ObjectMapper om = objectMapperForDateFormat(dateFormat);

        // Serialize directly to JSON string and build the response
        // We used to do StreamingOutput but Quarkus doesn't support it
        String json = om.writeValueAsString(messages);

        return ResponseEntity.ok().contentType(JSON_UTF8).body(json);

    }

    /**
     * {@inheritDoc}
     */
    @GetMapping(value = "/message/{messageId}", produces = "application/json;charset=UTF-8")
    @Operation(description = "Returns the public (published, cancelled or expired) NW or NM message details")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MessageVo.class)
            )
    )
    public ResponseEntity<String> messageDetails(
            @Parameter(description = "The message UID or short ID", example = "NM-1275-16")
            @PathVariable("messageId") String messageId,

            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language,

            @Parameter(description = "Whether to rewrite all embedded links and paths to be absolute URL's", example = "true")
            @RequestParam(name = "externalize", defaultValue = "true") boolean externalize,

            @Parameter(description = "The date format to use for JSON date-time encoding. Either 'UNIX_EPOCH' or 'ISO_8601'", example = "UNIX_EPOCH")
            @RequestParam(name = "dateFormat", defaultValue = "UNIX_EPOCH") JsonDateFormat dateFormat

    ) throws Exception {

        // Perform the search
        Message message = super.getMessage(messageId);

        if (message == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No message found with ID: " + messageId);
        } else {

            // Convert message to value objects and externalize message links, if requested
            MessageVo result = toMessageVo(message, language, externalize);

            // Depending on the dateFormat param, either use UNIX epoch or ISO-8601
            ObjectMapper om = objectMapperForDateFormat(dateFormat);

            // Serialize directly to JSON string and build the response
            // We used to do StreamingOutput but Quarkus doesn't support it
            String json = om.writeValueAsString(result);

            return ResponseEntity.ok().contentType(JSON_UTF8).body(json);
        }
    }

    /**
     * Returns the XSD for the Message class.
     * Two XSDs are produced, "schema1.xsd" and "schema2.xsd". The latter is the main schema for
     * Message and will import the former.
     * @return the XSD for the Message class
     */
    @GetMapping(value = "/xsd/{schemaFile}", produces = "application/xml;charset=UTF-8")
    @Operation(description = "Returns XSD model of the Message class")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = String.class)
            )
    )
    @Tag(ref = "message")
    public ResponseEntity<String> getMessageXsd(
            @Parameter(description = "The schema file, either schema1.xsd or schema2.xsd", example="schema2.xsd")
            @PathVariable("schemaFile")
                    String schemaFile) throws Exception {

        if (!schemaFile.equals("schema1.xsd") && !schemaFile.equals("schema2.xsd")) {
            throw new NiordWebException("Only 'schema1.xsd' and 'schema2.xsd' allowed", 400);
        }

        JAXBContext jaxbContext = JAXBContext.newInstance(MessageVo.class);

        Map<String, StringWriter> result = new HashMap<>();
        SchemaOutputResolver sor = new SchemaOutputResolver() {
            @Override
            public Result createOutput(String namespaceURI, String suggestedFileName) throws IOException {
                StringWriter out = new StringWriter();
                result.put(suggestedFileName, out);
                StreamResult result = new StreamResult(out);
                result.setSystemId(app.getBaseUri() + "/rest/public/v1/xsd/" + suggestedFileName);
                return result;
            }

        };

        // Generate the schemas
        jaxbContext.generateSchema(sor);

        // The answer names the file. Without a Content-Disposition of its own, Spring's guard against reflected
        // file downloads adds "inline;filename=f.txt" for an .xsd path, and a browser saves the schema as f.txt.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline().filename(schemaFile).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(XML_UTF8)
                .body(result.get(schemaFile).toString());
    }

    /**
     * Convert the message to a value object representation.
     * If requested, rewrite all links to make them external URLs.
     * @param msg the message to convert to a value object
     * @param externalize whether to rewrite all links to make them external URLs
     * @return the message value object representation
     **/
    private MessageVo toMessageVo(Message msg, String language, boolean externalize) {

        // Sanity check
        if (msg == null) {
            return null;
        }

        // Convert the message to a value object
        DataFilter filter = Message.MESSAGE_DETAILS_FILTER.lang(language);
        MessageVo message = msg.toVo(MessageVo.class, filter);

        // If "externalize" is set, rewrite all links to make them external
        if (externalize) {
            String baseUri = app.getBaseUri();

            if (message.getParts() != null) {
                String from = concat("/rest/repo/file", msg.getRepoPath());
                String to = concat(baseUri, from);
                message.getParts().forEach(mp -> mp.rewriteRepoPath(from, to));
            }
            if (message.getAttachments() != null) {
                String to = concat(baseUri, "rest/repo/file",  msg.getRepoPath());
                message.getAttachments().forEach(att -> att.rewriteRepoPath( msg.getRepoPath(), to));
            }
        }

        return message;
    }

    /***************************
     * Publication end-points
     ***************************/

    /**
     * Searches for publications
     */
    @GetMapping(value = "/publications", produces = "application/json;charset=UTF-8")
    @Operation(description = "Returns the publications")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PublicationVo.class, type= SchemaType.ARRAY)
            )
    )
    @Tag(ref = "publications")
    public ResponseEntity<String> searchPublications(

            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language,

            @Parameter(description = "Timestamp (Unix epoch) for the start date of the publications")
            @RequestParam(name = "from", required = false) Long from,

            @Parameter(description = "Timestamp (Unix epoch) for the end date of the publications")
            @RequestParam(name = "to", required = false) Long to,

            @Parameter(description = "Whether to rewrite all embedded links and paths to be absolute URL's", example = "true")
            @RequestParam(name = "externalize", defaultValue = "true") boolean externalize,

            @Parameter(description = "The date format to use for JSON date-time encoding. Either 'UNIX_EPOCH' or 'ISO_8601'", example = "UNIX_EPOCH")
            @RequestParam(name = "dateFormat", defaultValue = "UNIX_EPOCH") JsonDateFormat dateFormat
    ) throws Exception {

        // If from and to-dates are unspecified, return the publications currently active
        if (from == null && to == null) {
            from = to = System.currentTimeMillis();
        }

        List<PublicationVo> publications = super.searchPublications(language, from, to);
        if (externalize) {
            publications.forEach(p -> externalize(p, app.getBaseUri()));
        }

        // Depending on the dateFormat param, either use UNIX epoch or ISO-8601
        ObjectMapper om = objectMapperForDateFormat(dateFormat);

        // Serialize directly to JSON string and build the response
        // We used to do StreamingOutput but Quarkus doesn't support it
        String json = om.writeValueAsString(publications);

        return ResponseEntity.ok().contentType(JSON_UTF8).body(json);
    }

    /**
     * Returns the details for the given publications
     */
    @GetMapping(value = "/publication/{publicationId}", produces = "application/json;charset=UTF-8")
    @Operation(description = "Returns the publication with the given ID")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PublicationVo.class)
            )
    )
    @Tag(ref = "publications")
    public ResponseEntity<String> publicationDetails(

            @Parameter(description = "The publication ID", example = "5eab7f50-d890-42d9-8f0a-d30e078d3d5a")
            @PathVariable("publicationId") String publicationId,

            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language,

            @Parameter(description = "Whether to rewrite all embedded links and paths to be absolute URL's", example = "true")
            @RequestParam(name = "externalize", defaultValue = "true") boolean externalize,

            @Parameter(description = "The date format to use for JSON date-time encoding. Either 'UNIX_EPOCH' or 'ISO_8601'", example = "UNIX_EPOCH")
            @RequestParam(name = "dateFormat", defaultValue = "UNIX_EPOCH") JsonDateFormat dateFormat
    ) throws Exception {

        PublicationVo result = super.getPublication(publicationId, language);

        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No publication found with ID: " + publicationId);
        } else {

            // Externalize publication links, if requested
            if (externalize) {
                externalize(result, app.getBaseUri());
            }

            // Depending on the dateFormat param, either use UNIX epoch or ISO-8601
            ObjectMapper om = objectMapperForDateFormat(dateFormat);

            // Serialize directly to JSON string and build the response
            // We used to do StreamingOutput but Quarkus doesn't support it
            String json = om.writeValueAsString(result);

            return ResponseEntity.ok().contentType(JSON_UTF8).body(json);
        }
    }

    /**
     * Rewrites the relative links of a publication into absolute URLs, in place.
     *
     * Deliberately pure: it takes the base URI rather than reading it from the
     * application, and it takes a value object rather than an entity. Both are
     * what let a publication that has no entity behind it -- a published issue of
     * a publication series -- go through exactly this code rather than a copy of
     * it.
     *
     * Already-absolute links are left alone, so running this twice is harmless.
     *
     * @param publication the publication to rewrite, may be null
     * @param baseUri     the base URI to resolve relative links against
     * @return the same instance, for chaining
     **/
    static PublicationVo externalize(PublicationVo publication, String baseUri) {

        // Sanity check
        if (publication == null || publication.getDescs() == null) {
            return publication;
        }

        publication.getDescs().stream()
                .filter(d -> StringUtils.isNotBlank(d.getLink()))
                .filter(d -> !d.getLink().matches("^(?i)(https?)://.*$"))
                .forEach(d -> d.setLink(concat(baseUri, d.getLink())));

        return publication;
    }

    /***************************
     * Area end-points
     ***************************/

    /**
     * Returns the details for the given area
     */
    @GetMapping(value = "/area/{areaId}", produces = "application/json;charset=UTF-8")
    @Operation(description = "Returns the area with the given ID")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = AreaVo.class)
            )
    )
    @Tag(ref = "areas")
    public ResponseEntity<?> areaDetails(

            @Parameter(description = "The area ID", example = "urn:mrn:iho:country:dk")
            @PathVariable("areaId") String areaId,

            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language
    ) {

        Area area = super.getArea(areaId);

        if (area == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No area found with ID: " + areaId);
        } else {

            // Convert area to value objects
            AreaVo result = area.toVo(AreaVo.class, DataFilter.get().fields(DataFilter.PARENT).lang(language));

            return ResponseEntity.ok().contentType(JSON_UTF8).body(result);
        }
    }

    /**
     * Returns the details for the given area
     */
    @GetMapping(value = "/area/{areaId}/sub-areas", produces = "application/json;charset=UTF-8")
    @Operation(description = "Returns the sub-areas of the area with the given ID")
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = AreaVo.class, type = SchemaType.ARRAY)
            )
    )
    @Tag(ref = "areas")
    public ResponseEntity<?> subAreas(

            @Parameter(description = "The area ID", example = "urn:mrn:iho:country:dk")
            @PathVariable("areaId") String areaId,

            @Parameter(description = "Two-letter ISO 639-1 language code", example = "en")
            @RequestParam(name = "lang", required = false) String language
    ) {

        Area area = super.getArea(areaId);

        if (area == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No area found with ID: " + areaId);
        } else {

            // NB: Area.getChildren() will return sub-areas in sibling sort order
            List<AreaVo> result = area.getChildren().stream()
                    .filter(Area::isActive)
                    .map(a -> a.toVo(AreaVo.class, DataFilter.get().lang(language)))
                    .collect(Collectors.toList());

            return ResponseEntity.ok().contentType(JSON_UTF8).body(result);
        }
    }

    /***************************
     * Utility functions
     ***************************/

    /**
     * Returns an ObjectMapper for the given date format.
     *
     * Package-private rather than private so the payload contract test can
     * serialize through the same mapper the endpoint uses. A test that builds its
     * own mapper proves nothing about what the endpoint emits.
     **/
    static ObjectMapper objectMapperForDateFormat(JsonDateFormat dateFormat) {
        return JsonMapper.builderWithJackson2Defaults()
                .configure(
                        DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
                        dateFormat == JsonDateFormat.UNIX_EPOCH)
                .build();
    }

    /** Concatenates the URI components **/
    private static String concat(String... paths) {
        StringBuilder result = new StringBuilder();
        if (paths != null) {
            Arrays.stream(paths)
                    .filter(StringUtils::isNotBlank)
                    .forEach(p -> {
                        if (result.length() > 0 && !result.toString().endsWith("/") && !p.startsWith("/")) {
                            result.append("/");
                        } else if (result.toString().endsWith("/") && p.startsWith("/")) {
                            p = p.substring(1);
                        }
                        result.append(p);
                    });
        }
        return result.toString();
    }

}
