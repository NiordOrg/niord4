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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.chart.Chart;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.message.DateInterval;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.message.Reference;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.MainType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.s124.mapper.S124Mapper;
import org.niord.s124.mapper.S124Mapper.GenerationType;
import org.niord.secom.util.S124Mrn;
import org.niord.test.xml.XmlAssert;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit test for the S-124 v2.0.0 generator ({@link S124Service} + {@link org.niord.s124.mapper.S124Mapper})
 * - the core of the S-124 export. It maps the bundled sample message ({@code /message.json} =
 * NW-014-17, a 2017 COASTAL_WARNING) and asserts the resulting GML <em>structure and content</em>.
 * Every generation is also validated against the S-124 v2.0.0 XSD inside
 * {@code S124Mapper.toValidatedXml}, so a passing test additionally proves schema validity.
 *
 * <p>The persistence/REST sides of S-124 are covered by {@code S124ServiceITTest} /
 * {@code S124RestServiceITTest}.</p>
 */
public class S124Test {

    /** The IHO S-62 producer code of the test deployment (niord.s124.producer-code) */
    private static final String PRODUCER_CODE = "DK00";

    /** The test deployment's country setting: a country other than DK, so nothing Danish can hide in the generator */
    private static final String COUNTRY = "SE";

    /** The test deployment's organisation setting */
    private static final String ORGANISATION = "Test Organisation";

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private MessageService messageService;
    private MessageSeriesService messageSeriesService;
    private DictionaryService dictionaryService;
    private NiordApp app;
    private S124Service service;

    /** The zone of the message series' domain, as MessageSeriesService.seriesZone answers it */
    private ZoneId seriesZone = ZoneOffset.UTC;

    @BeforeEach
    public void setUp() {
        messageService = mock(MessageService.class);
        messageSeriesService = mock(MessageSeriesService.class);
        when(messageSeriesService.seriesZone(any())).thenAnswer(a -> seriesZone);
        dictionaryService = mock(DictionaryService.class);
        app = mock(NiordApp.class);
        when(app.getLanguage(anyString())).thenAnswer(a -> a.getArgument(0));
        // The deployment's model languages, in model order (the seed's ["da", "en"])
        when(app.getLanguages()).thenReturn(new String[] {"da", "en"});
        when(app.getCountry()).thenReturn(COUNTRY);
        when(app.getOrganisation()).thenReturn(ORGANISATION);
        service = new S124Service(messageService, messageSeriesService, dictionaryService, app, PRODUCER_CODE);
    }

    /** Loads the bundled sample message (NW-014-17) as a Message entity */
    private Message sampleMessage(String language) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SystemMessageVo messageVo = objectMapper.readValue(getClass().getResourceAsStream("/message.json"), SystemMessageVo.class);
        messageVo.sort(language);
        return new Message(messageVo, Message.MESSAGE_DETAILS_FILTER);
    }

    private String renderSampleGml(String language) throws Exception {
        return service.generateGML(sampleMessage(language), language, GenerationType.FINAL);
    }

    @Test
    public void englishGmlHasExpectedStructureAndContent() throws Exception {
        String gml = renderSampleGml("en");

        XmlAssert xml = XmlAssert.parse(gml); // also proves the GML is well-formed

        // Root S-124 v2.0.0 dataset, gml:id = the dataset id (the message short ID)
        xml.assertRoot("Dataset")
           .assertRootAttr("id", "NW-014-17");

        // The message has geometry, so a bounding box envelope is emitted
        xml.assertHasElement("boundedBy")
           .assertHasElement("Envelope");

        // S-100 dataset identification: S-124 product edition 2.0.0, the S-100 Part 17 clause
        // 17-4.3 file identifier (124 + producer code + alphanumeric short id + .GML) and the
        // Table 10b-4 application profile "1" of a base dataset
        xml.assertElementText("productIdentifier", "S-124")
           .assertElementText("productEdition", "2.0.0")
           .assertElementText("encodingSpecification", "S-100 Part 10b")
           .assertElementText("applicationProfile", "1")
           .assertElementText("datasetFileIdentifier", "124DK00NW01417.GML")
           .assertElementText("datasetLanguage", "eng");

        // Exactly one preamble carrying the message-series identifier: coastal warning #14 of
        // 2017 in series dma-nw, produced by the agency with S-62 code DK00 (S-124 clause 4.3.3
        // takes the code, not the name), with the interoperability MRN matching S124Mrn.of(); the nationality, the ids and the MRN
        // carry the country setting (SE here: nothing in the generator is Danish)
        xml.assertElementCount("NavwarnPreamble", 1)
           .assertElementText("warningNumber", "14")
           .assertElementText("year", "2017")
           .assertElementText("nameOfSeries", "dma-nw")
           .assertElementText("nationality", COUNTRY)
           .assertElementText("agencyResponsibleForProduction", PRODUCER_CODE)
           .assertElementText("interoperabilityIdentifier", "urn:mrn:iho:nw:se:nw-014-17")
           .assertElementTextContains("warningType", "Coastal Navigational Warning");

        // General area (from the message area), locality (from the vicinity) and the title
        xml.assertElementTextContains("generalArea", "Kattegat")
           .assertElementTextContains("locality", "Randers Fiord")
           .assertElementTextContains("navwarnTitle", "Light buoy replaced");

        // The single DETAILS part renders as a NavwarnPart with a Point geometry and the
        // plain-text details - in both model languages the message has them in, English
        // (the requested language) first
        xml.assertElementCount("NavwarnPart", 1)
           .assertHasElement("Point")
           .assertElementCount("information", 2)
           .assertElementTextContains("warningInformation", "replaced with a green buoy")
           .assertElementTextContains("warningInformation", "Sydkardinalen");
        assertEquals(List.of("eng", "dan"), xml.texts("language").stream().distinct().toList(),
                "every language tag is one of the emitted languages, primary first");

        // No message references -> no References members
        xml.assertNoElement("References");
    }

    /**
     * A message naming a chart validates: S-124 2.0's {@code chartAffected} requires an {@code editionDate} after the
     * {@code chartNumber} ({@code chartPlanNumber} and {@code lastNoticeDate} are optional), and
     * {@code affectedChartPublications} requires a {@code language}; a Niord chart has no edition date and the mapper
     * set no language, so no affected chart publication is written (owner, 2026-10-23, kept 2026-10-24;
     * be/docs/s124-secom-spec-gap-analysis.md §5)
     */
    @Test
    public void aMessageNamingAChartValidatesWithoutAnAffectedChartPublication() throws Exception {
        Message message = sampleMessage("en");
        Chart chart = new Chart();
        chart.setChartNumber("101");
        message.getCharts().add(chart);

        XmlAssert xml = XmlAssert.parse(service.generateGML(message, "en", GenerationType.FINAL));

        xml.assertNoElement("affectedChartPublications")
           .assertNoElement("chartAffected");
    }

    @Test
    public void danishGmlUsesDanishLocalityAndDetails() throws Exception {
        String gml = renderSampleGml("da");

        XmlAssert xml = XmlAssert.parse(gml);

        xml.assertRoot("Dataset");
        // Danish is the primary: the dataset language, and the first of the two locality names
        xml.assertElementText("datasetLanguage", "dan")
           .assertElementTextContains("locality", "Randers Fjord")
           .assertElementTextContains("locality", "Randers Fiord")
           .assertElementTextContains("generalArea", "Kattegat");
        assertEquals("Randers Fjord", xml.texts("text").stream()
                .filter(t -> t.startsWith("Randers")).findFirst().orElseThrow(),
                "the primary language's name comes first");
        // Series facts are language-independent
        xml.assertElementText("warningNumber", "14")
           .assertElementText("year", "2017");
    }

    @Test
    public void aLanguageRestrictionNarrowsTheDatasetToThoseLanguages() throws Exception {
        S124Mapper.Generated generated = service.generate(sampleMessage("en"), List.of("en"), GenerationType.FINAL);

        assertEquals(List.of("en"), generated.languages());
        XmlAssert xml = XmlAssert.parse(generated.xml());
        xml.assertElementText("datasetLanguage", "eng")
           .assertElementCount("information", 1)
           .assertElementCount("navwarnTitle", 1)
           .assertElementTextContains("warningInformation", "replaced with a green buoy");
        assertEquals(List.of("eng"), xml.texts("language").stream().distinct().toList(),
                "nothing Danish leaks into an English-only dataset");
    }

    @Test
    public void languagesTheMessageHasNoContentInAreNotEmitted() throws Exception {
        // The sample has Danish and English; a candidate with nothing to say is dropped, and
        // the dataset is spelled in the first candidate that has content
        Message message = sampleMessage("en");
        message.getDescs().removeIf(d -> "da".equals(d.getLang()));
        message.getParts().forEach(part -> part.getDescs().removeIf(d -> "da".equals(d.getLang())));

        S124Mapper.Generated generated = service.generate(message, List.of("da", "en"), GenerationType.FINAL);

        assertEquals(List.of("en"), generated.languages(), "Danish was preferred but the message has none");
        XmlAssert.parse(generated.xml())
                .assertElementText("datasetLanguage", "eng")
                .assertElementCount("information", 1);
    }

    @Test
    public void languagesForOrdersTheModelLanguagesUnderTheRestriction() {
        // No preference, no restriction: English first (the SECOM default), then the rest
        assertEquals(List.of("en", "da"), service.languagesFor(null, List.of()));
        assertEquals(List.of("en", "da"), service.languagesFor(null, null));
        // A preferred language leads when it is a model language
        assertEquals(List.of("da", "en"), service.languagesFor("da", List.of()));
        // A restriction narrows the candidates; English still leads when it is among them
        assertEquals(List.of("en"), service.languagesFor(null, List.of("en")));
        assertEquals(List.of("da"), service.languagesFor(null, List.of("da")));
        assertEquals(List.of("da"), service.languagesFor("en", List.of("da")),
                "a preference outside the restriction does not widen it");
        // A restriction naming no model language restricts nothing
        assertEquals(List.of("en", "da"), service.languagesFor(null, List.of("fr")));
    }

    @Test
    public void responsibleAgencyNameComesFromTheDictionaryButNeverEntersTheGml() throws Exception {
        // The agency NAME (the dictionary text, else the organisation setting) is the producing
        // organization of the exchange-set catalogues. The GML identifies the agency by its S-62
        // producer code (S-124 clause 4.3.3), which the name must never leak into - a pre-existing
        // bug the bindings' conformance check now rejects outright.
        assertEquals(ORGANISATION, service.responsibleAgency(), "no text: the organisation setting");
        when(dictionaryService.value("message", "en", "msg.responsible.agency")).thenReturn("Custom Agency");
        assertEquals("Custom Agency", service.responsibleAgency(), "the text wins");
        when(dictionaryService.value("message", "en", "msg.responsible.agency")).thenReturn("  ");
        assertEquals(ORGANISATION, service.responsibleAgency(), "a blank text counts as none");

        String gml = renderSampleGml("en");
        XmlAssert.parse(gml).assertElementText("agencyResponsibleForProduction", PRODUCER_CODE);
        assertFalse(gml.contains(ORGANISATION), "the organisation name never enters the GML");
    }

    @Test
    public void blankOrganisationWithoutTextFailsNamingTheSetting() {
        // The producing agency is mandatory in an exchange-set catalogue: with neither the text
        // nor the organisation setting there is no name to give, so the error names the setting
        when(dictionaryService.value("message", "en", "msg.responsible.agency")).thenReturn("");
        for (String blank : new String[] { null, "", "  " }) {
            when(app.getOrganisation()).thenReturn(blank);
            IllegalStateException e = assertThrows(IllegalStateException.class, service::responsibleAgency);
            assertTrue(e.getMessage().contains("the organisation setting is required"), e.getMessage());
        }
    }

    @Test
    public void countryThatIsNotATwoLetterCodeFailsNamingTheSetting() {
        // A value such as 'D K' would make the gml:ids and the MRN invalid and fail inside the
        // XSD validator without naming the setting
        for (String invalid : new String[] { "D K", "DK.", "DNK", "D" }) {
            when(app.getCountry()).thenReturn(invalid);
            IllegalStateException e = assertThrows(IllegalStateException.class, service::country);
            assertTrue(e.getMessage().contains("the country setting '" + invalid + "' is not a two-letter"),
                    e.getMessage());
        }
    }

    @Test
    public void blankCountryFailsGenerationNamingTheSetting() throws Exception {
        // The country setting is required (the base product's file gives XX, a country's file its
        // own); a blank value cannot name the warnings' nationality, so generation fails
        Message message = sampleMessage("en");
        for (String blank : new String[] { null, "", "  " }) {
            when(app.getCountry()).thenReturn(blank);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.generateGML(message, "en", GenerationType.FINAL));
            assertTrue(e.getMessage().contains("the country setting is required"), e.getMessage());
        }
    }

    @Test
    public void countryIsTrimmedAndUpperCased() throws Exception {
        when(app.getCountry()).thenReturn(" gl ");
        assertEquals("GL", service.country());
        XmlAssert.parse(renderSampleGml("en"))
                .assertElementText("nationality", "GL")
                .assertElementText("interoperabilityIdentifier", "urn:mrn:iho:nw:gl:nw-014-17");
    }

    @Test
    public void missingProducerCodeFailsGenerationWithAnActionableError() throws Exception {
        // A deployment without niord.s124.producer-code boots (the generic app has none) but
        // cannot produce a conformant dataset: the S-62 code is mandatory in every one
        S124Service unconfigured = new S124Service(messageService, messageSeriesService, dictionaryService, app, "");
        Message message = sampleMessage("en");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> unconfigured.generateGML(message, "en", GenerationType.FINAL));
        assertTrue(e.getMessage().contains("niord.s124.producer-code is required"), e.getMessage());
    }

    @Test
    public void malformedProducerCodeIsRejectedAtConstruction() {
        // Lowercase, wrong lengths, whitespace, punctuation and non-ASCII: rejected, never repaired
        for (String invalid : new String[] { "dk00", "DK0", "DK000", "DK 0", "DK-0", "DKØ0" }) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new S124Service(messageService, messageSeriesService, dictionaryService, app, invalid),
                    "producer code '" + invalid + "' must be rejected");
            assertTrue(e.getMessage().contains("not a valid IHO S-62 producer code"), e.getMessage());
        }
    }

    @Test
    public void languageTagsUseIso639_2AndNeverRelabelAnUnmappedLanguage() throws Exception {
        // Regression (S124-1): normLang collapsed every language but da/de to "en", so a part in
        // any other configured language - Norwegian being the realistic case - was tagged
        // <language>en</language> with Norwegian text inside it, and the tag is the only thing
        // telling a consumer what language a text is in. The tags now use the ISO 639-2/T
        // three-letter codes datasetLanguage has always used, so one dataset spells its
        // languages one way.
        Message message = sampleMessage("en");
        message.createDesc("no").setTitle("Lysbøye erstattet");
        message.getParts().getFirst().createDesc("no").setDetails("Lysbøyen er erstattet med en grønn bøye");
        // Norwegian is a configured model language of this deployment
        when(app.getLanguages()).thenReturn(new String[] {"da", "en", "no"});

        String gml = service.generateGML(message, "no", GenerationType.FINAL);

        XmlAssert.parse(gml).assertElementText("datasetLanguage", "nor");
        Set<String> tags = new HashSet<>();
        Matcher matcher = Pattern.compile("<(?:\\w+:)?language>([^<]*)</").matcher(gml);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        assertTrue(tags.contains("nor"), "the Norwegian title and details must be tagged nor, got " + tags);
        assertTrue(tags.contains("eng") && tags.contains("dan"),
                "the English and Danish titles keep their own tags, got " + tags);
        assertTrue(tags.stream().allMatch(t -> t.matches("[a-z]{3}")),
                "every language tag must be a three-letter ISO 639-2 code, got " + tags);
    }

    @Test
    public void previewGenerationSupportsUnnumberedMessages() throws Exception {
        Message message = sampleMessage("en");
        message.setNumber(null);

        // FINAL generation of an un-numbered warning is rejected...
        assertThrows(IllegalArgumentException.class,
                () -> service.generateGML(message, "en", GenerationType.FINAL));

        // ...but PREVIEW emits a schema-valid dataset with warning number 0 and a PREVIEW title
        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);
        XmlAssert.parse(gml)
                .assertElementText("warningNumber", "0")
                .assertElementTextContains("datasetTitle", "PREVIEW");
    }

    @Test
    public void previewOfNeverPublishedDraftIsSchemaValidWithPublicationTimeAndYear() throws Exception {
        // Regression (S124-3): the schema declares both publicationTime and the series year as
        // minOccurs=1, but the mapper only emitted them when the message carried a publishDateFrom.
        // A draft that has never been published has none, so PREVIEW - whose whole purpose is
        // rendering not-yet-publishable messages - failed XSD validation. The preview must fall
        // back to the draft's own timestamp instead.
        Message message = sampleMessage("en");
        message.setNumber(null);                        // never assigned a warning number
        message.setPublishDateFrom(null);               // never published
        message.setUpdated(new Date(1495000000000L));   // 2017-05-17, the draft's last-edited time

        // The old code threw an XSD validation error here (missing publicationTime, then year).
        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);

        XmlAssert.parse(gml)
                .assertRoot("Dataset")
                .assertElementTextContains("publicationTime", "2017-05-17") // fell back to 'updated'
                .assertElementText("year", "2017")
                .assertElementText("warningNumber", "0");                   // PREVIEW un-numbered fallback
    }

    @Test
    public void unsavedMultiPartMessageGivesEachPartADistinctXmlId() throws Exception {
        // Regression (S124-4): an unsaved message has null part ids, so every NavwarnPart rendered
        // as "P.<msg>.null"; with two or more parts the xml:ids collided and - xml:id being a
        // schema ID - the dataset failed XSD validation and could not be generated at all. Collisions
        // must be disambiguated the same way two References to the same target already are.
        Message message = sampleMessage("en");          // one unsaved (null-id) part, from the sample
        MessagePart second = new MessagePart();
        second.createDesc("en").setDetails("A second navigational-warning part");
        message.addPart(second);                        // a second unsaved (null-id) part

        // Validated against the XSD inside toValidatedXml - duplicate part ids would throw here.
        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        XmlAssert.parse(gml).assertElementCount("NavwarnPart", 2);
        assertTrue(gml.contains("id=\"P.SE.NW-014-17.null\""),
                "the first null-id part keeps the un-suffixed id");
        assertTrue(gml.contains("id=\"P.SE.NW-014-17.null.2\""),
                "the second null-id part must get a disambiguated id");
    }

    @Test
    public void previewOfWhollyUnsavedMultiPartDraftIsSchemaValidWithDistinctPreviewIds() throws Exception {
        // The scenario the first editor-preview integration hits: a draft with no short id, no
        // warning number and no publish date, still being composed with several parts. Every part
        // id collapses to "P.SE.PREVIEW.null" and the preamble lacks publicationTime/year - so
        // without the fixes the preview cannot be generated at all. Exercises S124-3 and S124-4
        // together.
        Message message = sampleMessage("en");
        message.setShortId(null);                       // -> toMessageId falls back to "SE.PREVIEW"
        message.setNumber(null);
        message.setPublishDateFrom(null);
        message.setUpdated(new Date(1495000000000L));
        MessagePart second = new MessagePart();
        second.createDesc("en").setDetails("A second navigational-warning part");
        message.addPart(second);

        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);

        XmlAssert.parse(gml)
                .assertRoot("Dataset")
                .assertElementCount("NavwarnPart", 2)
                .assertElementText("warningNumber", "0")
                .assertElementTextContains("publicationTime", "2017-05-17");
        assertTrue(gml.contains("id=\"P.SE.PREVIEW.null\""),
                "the first preview part keeps the un-suffixed id");
        assertTrue(gml.contains("id=\"P.SE.PREVIEW.null.2\""),
                "the second preview part must get a disambiguated id");
    }

    @Test
    public void previewOfTypelessDraftDefaultsWarningTypeAndIsSchemaValid() throws Exception {
        // Regression (S124-3, completeness): warningType is minOccurs=1 in messageSeriesIdentifier and
        // carries a required label enum, yet was only set when the message had a type. A fresh editor
        // draft (e.g. from newTemplateMessage) has not picked a scope yet, so PREVIEW still failed XSD
        // on the missing warningType even after the publicationTime/year fixes. The preview must default
        // the scope instead of omitting the element.
        Message message = sampleMessage("en");
        message.setType(null);                          // scope not chosen yet (mainType stays NW)
        message.setNumber(null);
        message.setPublishDateFrom(null);
        message.setUpdated(new Date(1495000000000L));

        // The old code omitted <warningType> here and threw an XSD validation error.
        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);

        XmlAssert.parse(gml)
                .assertRoot("Dataset")
                .assertElementTextContains("warningType", "Coastal Navigational Warning"); // preview default
    }

    @Test
    public void previewPublicationTimeFallsBackToCreatedWhenNotUpdated() throws Exception {
        // The publicationTime/year fallback cascades updated -> created -> now; pin the deterministic
        // created arm (a transient draft carrying only a creation time) so a regression that dropped it
        // and fell through to now() would fail loudly rather than silently emit today's date.
        Message message = sampleMessage("en");
        message.setNumber(null);
        message.setPublishDateFrom(null);
        message.setUpdated(null);                        // no edit timestamp...
        message.setCreated(new Date(1495000000000L));    // ...only a creation time (2017-05-17)

        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);

        XmlAssert.parse(gml)
                .assertElementTextContains("publicationTime", "2017-05-17") // fell back to 'created'
                .assertElementText("year", "2017");
    }

    @Test
    public void noticesToMarinersAreRejected() throws Exception {
        Message message = sampleMessage("en");
        message.setMainType(MainType.NM);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.generateGML(message, "en", GenerationType.FINAL));
        assertTrue(e.getMessage().contains("Notices to Mariners"));
    }

    @Test
    public void generateGmlByMessageIdResolvesTheMessage() throws Exception {
        Message message = sampleMessage("en");
        when(messageService.resolveMessage("NW-014-17")).thenReturn(message);

        String gml = service.generateGML("NW-014-17", "en");
        XmlAssert.parse(gml).assertRoot("Dataset");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.generateGML("NW-999-99", "en"));
        assertTrue(e.getMessage().contains("Message not found"));
    }

    @Test
    public void generatedGmlIsByteStableForUnchangedMessages() throws Exception {
        // The SECOM dataset store diffs regenerated GML against the stored GML to decide
        // whether to push an update - generation must be deterministic for unchanged input
        Message message = sampleMessage("en");
        String first = service.generateGML(message, "en", GenerationType.FINAL);
        String second = service.generateGML(message, "en", GenerationType.FINAL);
        assertEquals(first, second, "unchanged message must generate byte-identical GML");
    }

    @Test
    public void genericObstructionWarningsCarryTheSpecialOperationsCodeValuePair() throws Exception {
        // Regression: the generic-obstruction fallback hand-paired code 9 with the Special
        // Operations label; the codelist maps Special Operations to 17 (cf. the IHO S-164
        // test datasets), and the XSD does not enforce label/code pairing, so the mismatch
        // was schema-valid but semantically wrong
        Message message = sampleMessage("en");
        message.getCategories().getFirst().getDescs().forEach(d -> d.setName("Obstruction"));
        // A subject matching none of the wreck/uncharted/depth templates -> the generic branch
        message.getParts().getFirst().getDescs().forEach(d -> d.setSubject("Unknown object adrift"));

        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        assertTrue(gml.contains("navwarnTypeGeneral code=\"17\">Special Operations"),
                "the label and code must come as the codelist pair (Special Operations = 17)");
    }

    @Test
    public void twoReferencesToTheSameMessageGetDistinctXmlIds() throws Exception {
        // Regression: both references resolve to the same target, so both References features
        // computed the same xml:id - a schema ID, so the duplicate failed XSD validation and
        // the dataset could no longer be generated at all
        Message message = sampleMessage("en");
        for (ReferenceType type : new ReferenceType[] { ReferenceType.REFERENCE, ReferenceType.CANCELLATION }) {
            Reference reference = new Reference();
            reference.setMessageId("NW-001-17");
            reference.setType(type);
            message.addReference(reference);
        }
        when(messageService.resolveMessage("NW-001-17")).thenReturn(referencedNw("NW-001-17"));

        // Validated against the XSD inside toValidatedXml - duplicate ids would throw here
        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        assertTrue(gml.contains("id=\"R.SE.NW-014-17.SE.NW-001-17\""),
                "the first References feature keeps the un-suffixed id");
        assertTrue(gml.contains("id=\"R.SE.NW-014-17.SE.NW-001-17.2\""),
                "the second References feature to the same target must get a disambiguated id");
    }

    @Test
    public void mrnFallsBackToTheMessageUidWhenShortIdIsMissing() throws Exception {
        // The GML interoperabilityIdentifier must always equal S124Mrn.of(message, country), which the
        // SECOM dataset store keys its rows by - including for messages without a short ID
        Message message = sampleMessage("en");
        message.setShortId(null);
        message.setUid("11ecb8e0-a234-4de8-9c88-1dd0e4f4c11d");

        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        XmlAssert.parse(gml).assertElementText("interoperabilityIdentifier", S124Mrn.of(message, COUNTRY));
    }

    @Test
    public void referencesRenderInMessageOrder() throws Exception {
        // Regression: references were copied into a HashMap, so References members (and the
        // per-part affects links) rendered in identity-hash order - unstable across JVM runs,
        // which the SECOM store's byte-diff would misread as a changed dataset and re-push
        Message message = sampleMessage("en");
        // Deliberately not in lexicographic order, to prove insertion order wins
        for (String id : new String[] { "REF-C", "REF-A", "REF-B" }) {
            Reference reference = new Reference();
            reference.setMessageId(id);
            reference.setType(ReferenceType.REFERENCE);
            message.addReference(reference);
            when(messageService.resolveMessage(id)).thenReturn(referencedNw(id));
        }

        String first = service.generateGML(message, "en", GenerationType.FINAL);
        String second = service.generateGML(message, "en", GenerationType.FINAL);
        assertEquals(first, second, "reference rendering must be deterministic");

        int posC = first.indexOf("urn:mrn:iho:nw:se:ref-c");
        int posA = first.indexOf("urn:mrn:iho:nw:se:ref-a");
        int posB = first.indexOf("urn:mrn:iho:nw:se:ref-b");
        assertTrue(posC >= 0 && posA >= 0 && posB >= 0, "all three references must be rendered");
        assertTrue(posC < posA && posA < posB,
                "References must render in the message's own reference order (C, A, B)");
    }

    @Test
    public void cancellationWarningsRenderAsCancellationOnlyDatasets() throws Exception {
        // The auto-created cancellation warning (docs/s124-cancellation-plan.md phase 2,
        // MessageService.composeCancellationWarning) is a born-published message with no parts
        // and a single CANCELLATION reference to the cancelled warning. The mapper needs no
        // cancellation-specific branches: this pins the S-124 section 9.3 method 2 / Table 8-1
        // "cancellation" dataset it renders for that shape.
        Message source = sampleMessage("en");
        when(messageService.resolveMessage(source.getUid())).thenReturn(source);

        Message cancellation = new Message();
        cancellation.setUid("22fdc9f1-b345-4ef9-8d99-2ee1f5a5d22e");
        cancellation.setMessageSeries(source.getMessageSeries());
        cancellation.setMainType(source.getMainType());
        cancellation.setType(source.getType());              // scope copied from the source
        cancellation.setStatus(Status.PUBLISHED);
        cancellation.setNumber(15);                          // assigned from the series sequence
        cancellation.setShortId("NW-015-17");
        cancellation.setPublishDateFrom(new Date(1495000000000L));
        cancellation.checkUpdateYear();
        cancellation.setCategories(source.getCategories());  // categories drive navwarnTypeGeneral
        cancellation.setAutoTitle(false);
        cancellation.createDesc("en").setTitle("Cancellation of NW-014-17");
        Reference reference = new Reference();
        reference.setMessageId(source.getUid());
        reference.setType(ReferenceType.CANCELLATION);
        cancellation.addReference(reference);

        // Validated against the S-124 XSD inside toValidatedXml
        String gml = service.generateGML(cancellation, "en", GenerationType.FINAL);

        XmlAssert.parse(gml)
                .assertRoot("Dataset")
                .assertRootAttr("id", "NW-015-17")
                // No parts of its own, so the extent S-124 clause 12.2.2 requires of every
                // distributed dataset is that of the warning it cancels
                .assertHasElement("boundedBy")
                .assertHasElement("Envelope")
                .assertElementCount("NavwarnPreamble", 1)
                .assertNoElement("NavwarnPart")               // cancellation-only: no parts
                .assertNoElement("cancellationDate")          // in force until manually retired
                .assertElementTextContains("navwarnTitle", "Cancellation of NW-014-17")
                .assertElementCount("References", 1)          // exactly one References feature
                .assertElementText("noMessageOnHand", "false");

        assertTrue(gml.contains("referenceCategory code=\"1\">Warning Cancellation"),
                "the reference must carry the Warning Cancellation codelist pair (category 1)");
        assertTrue(gml.contains("urn:mrn:iho:nw:se:nw-014-17"),
                "the References feature must point at the cancelled warning's MRN");
    }

    @Test
    public void warningWithoutPositionsTakesItsExtentFromItsArea() throws Exception {
        // A warning stating an area of applicability but no positions still has an extent -
        // the area's (or its nearest geotagged ancestor's) - which S-124 clause 12.2.2 requires
        // of every dataset the exchange-set factory distributes
        Message message = sampleMessage("en");
        message.getParts().getFirst().setGeometry(null);
        Area region = new Area();
        region.setGeometry(JtsConverter.toJtsExtent(54.0, 8.0, 58.0, 14.0));
        message.getAreas().getFirst().setParent(region);     // the area itself has no geometry

        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        XmlAssert.parse(gml)
                .assertHasElement("boundedBy")
                .assertElementText("lowerCorner", "54.0000000 8.0000000")   // the region's extent,
                .assertElementText("upperCorner", "58.0000000 14.0000000")  // lat,lon corners
                .assertNoElement("Point");                                  // no positions of its own
    }

    @Test
    public void finalGmlLeavesOutReferencesToNonPublicWarnings() throws Exception {
        // The FINAL dataset is served anonymously by /rest/S-124 and pushed to SECOM subscribers,
        // so a reference to a warning the public may not see must not resolve: no References
        // feature, no affects link and no trace of the referenced warning's series identifier.
        Message message = sampleMessage("en");
        String[] hiddenIds = { "NW-901-17", "NW-902-17", "NW-903-17" };
        Status[] hiddenStatuses = { Status.DRAFT, Status.VERIFIED, Status.DELETED };
        for (int i = 0; i < hiddenIds.length; i++) {
            addReference(message, hiddenIds[i], ReferenceType.REFERENCE);
            Message referenced = referencedNw(hiddenIds[i]);
            referenced.setStatus(hiddenStatuses[i]);
            when(messageService.resolveMessage(hiddenIds[i])).thenReturn(referenced);
        }

        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        XmlAssert.parse(gml)
                .assertNoElement("References")
                .assertNoElement("affects");
        for (String id : hiddenIds) {
            assertFalse(gml.toLowerCase().contains(id.toLowerCase()),
                    "the non-public referenced warning " + id + " must not appear in the GML");
        }
    }

    @Test
    public void finalGmlStillRendersReferencesToPublicWarnings() throws Exception {
        // Control for the rule above: published, cancelled and expired targets keep their
        // References feature and the per-part affects link.
        Message message = sampleMessage("en");
        String[] publicIds = { "NW-911-17", "NW-912-17", "NW-913-17" };
        Status[] publicStatuses = { Status.PUBLISHED, Status.CANCELLED, Status.EXPIRED };
        for (int i = 0; i < publicIds.length; i++) {
            addReference(message, publicIds[i], ReferenceType.REFERENCE);
            Message referenced = referencedNw(publicIds[i]);
            referenced.setStatus(publicStatuses[i]);
            when(messageService.resolveMessage(publicIds[i])).thenReturn(referenced);
        }

        String gml = service.generateGML(message, "en", GenerationType.FINAL);

        XmlAssert.parse(gml)
                .assertElementCount("References", 3)
                .assertElementCount("affects", 3);
        for (String id : publicIds) {
            assertTrue(gml.contains("urn:mrn:iho:nw:se:" + id.toLowerCase()),
                    "the public referenced warning " + id + " must be rendered");
        }
    }

    @Test
    public void previewStillRendersReferencesToNonPublicWarnings() throws Exception {
        // The editor-only PREVIEW shows the message as it is being written, drafts included.
        Message message = sampleMessage("en");
        addReference(message, "NW-921-17", ReferenceType.REFERENCE);
        Message referenced = referencedNw("NW-921-17");
        referenced.setStatus(Status.DRAFT);
        when(messageService.resolveMessage("NW-921-17")).thenReturn(referenced);

        String gml = service.generateGML(message, "en", GenerationType.PREVIEW);

        XmlAssert.parse(gml).assertElementCount("References", 1);
        assertTrue(gml.contains("urn:mrn:iho:nw:se:nw-921-17"),
                "a preview resolves the draft reference");
    }

    @Test
    public void extentOfAWarningWithoutPositionsNeverComesFromANonPublicReference() throws Exception {
        // A warning without positions takes its extent from the warnings it references before its
        // area (datasetBoundingShape). A referenced draft's positions must not leak through that
        // extent: the draft is left out, so the extent falls back to the area.
        Message referenced = sampleMessage("en");                // has a Point in Randers Fiord
        referenced.setUid("33aec1f2-c456-4f00-9eaa-3ff2a6b6e33f");
        referenced.setShortId("NW-931-17");
        referenced.setStatus(Status.DRAFT);
        when(messageService.resolveMessage("NW-931-17")).thenReturn(referenced);

        Message message = sampleMessage("en");
        message.getParts().getFirst().setGeometry(null);         // no positions of its own
        Area region = new Area();
        region.setGeometry(JtsConverter.toJtsExtent(54.0, 8.0, 58.0, 14.0));
        message.getAreas().getFirst().setParent(region);
        addReference(message, "NW-931-17", ReferenceType.REFERENCE);

        XmlAssert.parse(service.generateGML(message, "en", GenerationType.FINAL))
                .assertElementText("lowerCorner", "54.0000000 8.0000000")   // the area's extent,
                .assertElementText("upperCorner", "58.0000000 14.0000000")  // not the draft's point
                .assertNoElement("References");

        // Control: once the referenced warning is published, its position is the extent
        referenced.setStatus(Status.PUBLISHED);
        XmlAssert published = XmlAssert.parse(service.generateGML(message, "en", GenerationType.FINAL));
        published.assertElementCount("References", 1);
        assertNotEquals("54.0000000 8.0000000", published.texts("lowerCorner").getFirst(),
                "a published reference supplies the extent before the area does");
    }

    /* ---------------------------------------------------------------- */
    /* The warning's year and dates in the series' zone (S124-5)        */
    /* ---------------------------------------------------------------- */

    /** 00:30 on 1 January 2027 in Copenhagen, still 2026 in UTC */
    private static final Date NEW_YEARS_EVE_2330_UTC = Date.from(Instant.parse("2026-12-31T23:30:00Z"));

    @Test
    public void theSeriesIdentifierCarriesTheStoredYear() throws Exception {
        // The stored year is the series' own (MessageSeriesService reads it in the domain's zone when it numbers
        // the message, and it is in the short id): S-124 takes it as it is, whatever zone the generation runs in
        seriesZone = ZoneOffset.UTC;
        Message message = sampleMessage("en");
        message.setPublishDateFrom(NEW_YEARS_EVE_2330_UTC);
        message.checkUpdateYear(COPENHAGEN);

        String gml = inUtcJvm(() -> service.generateGML(message, "en", GenerationType.FINAL));

        XmlAssert.parse(gml)
                .assertElementText("year", "2027")
                .assertElementTextContains("publicationTime", "2026-12-31T23:30");
    }

    @Test
    public void withoutAStoredYearTheYearIsReadInTheSeriesZone() throws Exception {
        seriesZone = COPENHAGEN;
        Message message = sampleMessage("en");          // built from a value object: no stored year
        message.setPublishDateFrom(NEW_YEARS_EVE_2330_UTC);

        String gml = inUtcJvm(() -> service.generateGML(message, "en", GenerationType.FINAL));

        XmlAssert.parse(gml).assertElementText("year", "2027");
    }

    @Test
    public void thePreviewYearOfANeverPublishedDraftIsReadInTheSeriesZone() throws Exception {
        seriesZone = COPENHAGEN;
        Message message = sampleMessage("en");
        message.setNumber(null);
        message.setPublishDateFrom(null);
        message.setUpdated(NEW_YEARS_EVE_2330_UTC);

        String gml = inUtcJvm(() -> service.generateGML(message, "en", GenerationType.PREVIEW));

        XmlAssert.parse(gml)
                .assertElementText("year", "2027")
                .assertElementTextContains("publicationTime", "2026-12-31T23:30");  // an instant, unchanged
    }

    @Test
    public void aReferencedWarningCarriesItsOwnStoredYear() throws Exception {
        seriesZone = ZoneOffset.UTC;
        Message message = sampleMessage("en");
        addReference(message, "NW-941-27", ReferenceType.REFERENCE);
        Message referenced = referencedNw("NW-941-27");
        referenced.setPublishDateFrom(NEW_YEARS_EVE_2330_UTC);
        referenced.checkUpdateYear(COPENHAGEN);
        when(messageService.resolveMessage("NW-941-27")).thenReturn(referenced);

        XmlAssert xml = XmlAssert.parse(inUtcJvm(() -> service.generateGML(message, "en", GenerationType.FINAL)));

        assertEquals(List.of("2017", "2027"), xml.texts("year"),
                "the warning's own year, then the referenced warning's stored year");
    }

    @Test
    public void anAllDayPeriodEastOfUtcKeepsItsDays() throws Exception {
        // An all-day period is 00:00 to 23:59:59 in the editor's zone (DateInterval): read in UTC it began a day early
        seriesZone = COPENHAGEN;
        Message message = withOneEventDate(sampleMessage("en"), "2026-06-01T00:00:00+02:00", "2026-06-03T23:59:59+02:00");

        XmlAssert xml = XmlAssert.parse(inUtcJvm(() -> service.generateGML(message, "en", GenerationType.FINAL)));

        assertEquals("2026-06-01", xml.firstText("dateStart"));
        assertEquals("2026-06-03", xml.firstText("dateEnd"));
    }

    @Test
    public void anAllDayPeriodWestOfUtcKeepsItsDays() throws Exception {
        // West of UTC, read in UTC it ended a day late
        seriesZone = LOS_ANGELES;
        Message message = withOneEventDate(sampleMessage("en"), "2026-06-01T00:00:00-07:00", "2026-06-03T23:59:59-07:00");

        XmlAssert xml = XmlAssert.parse(inUtcJvm(() -> service.generateGML(message, "en", GenerationType.FINAL)));

        assertEquals("2026-06-01", xml.firstText("dateStart"));
        assertEquals("2026-06-03", xml.firstText("dateEnd"));
    }

    /** Gives the message's first part one all-day event date, from and to as ISO date-times with an offset */
    private static Message withOneEventDate(Message message, String from, String to) {
        MessagePart part = message.getParts().getFirst();
        part.getEventDates().clear();
        part.addEventDates(new DateInterval(true,
                Date.from(OffsetDateTime.parse(from).toInstant()), Date.from(OffsetDateTime.parse(to).toInstant())));
        return message;
    }

    /**
     * Runs the body with the JVM's default zone set to UTC, the zone of the image, and restores it after: a
     * workstation JVM in the series' own zone would otherwise hide a date formed in the JVM's zone
     */
    private static <T> T inUtcJvm(Callable<T> body) throws Exception {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
            return body.call();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /** Adds a reference of the given type to the given message id */
    private static void addReference(Message message, String messageId, ReferenceType type) {
        Reference reference = new Reference();
        reference.setMessageId(messageId);
        reference.setType(type);
        message.addReference(reference);
    }

    /** A minimal published NW usable as a reference target */
    private Message referencedNw(String shortId) {
        Message message = new Message();
        message.setMainType(MainType.NW);
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.PUBLISHED);
        message.setShortId(shortId);
        message.setNumber(1);
        message.setPublishDateFrom(new Date(1494409642000L));
        return message;
    }
}
