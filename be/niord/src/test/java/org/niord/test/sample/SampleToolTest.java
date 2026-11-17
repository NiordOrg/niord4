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
package org.niord.test.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample's tool (docs/fe-country-split-plan.md P9): it writes an attachment only at a path of the repository's
 * layout below its output folder, and counts the link rows the message import writes. It names the base rows by
 * natural key in pg_dump's INSERTs and writes the guard's checks of those keys (docs/code-data-config-plan.md §8),
 * in the shapes of {@link SampleReferences}, each of which reads back as it was written.
 */
class SampleToolTest {

    /** A uid of the layout's shape, not one of the sample's */
    static final String UID = "0be11e00-1111-4222-8333-000000000001";

    static final String GOOD = "messages/0/be/" + UID + "/1/sketch.png";

    @TempDir
    Path dir;

    @Test
    void anAttachmentInTheLayoutIsWritten() throws IOException {
        Path out = dir.resolve("out");
        assertEquals(1, SampleTool.writeAttachments(messages(attachment(GOOD)), out));
        assertTrue(Files.isRegularFile(out.resolve(GOOD)));
    }

    @Test
    void anAbsoluteEscapingOrUnlaidPathIsRefused() throws IOException {
        Path out = dir.resolve("out");
        // A path outside the layout first, so that a tool without the rule fails here before it writes anywhere else
        for (String path : List.of("x.png", "messages/0/be/" + UID + "/x.png", "messages/../x.png", "/tmp/x.png",
                "C:/x.png", "messages/0/be/" + UID + "/1/../../../../../../x.png")) {
            Path json = messages(attachment(path));
            IOException e = assertThrows(IOException.class, () -> SampleTool.writeAttachments(json, out), path);
            assertTrue(e.getMessage().contains(path), e.getMessage());
        }
        assertFalse(Files.exists(out.resolve("x.png")));
    }

    @Test
    void theLinkRowsAreCountedInTheMessages() throws IOException {
        String two = "{\"id\": \"a\", \"areas\": [{}, {}], \"categories\": [{}], \"charts\": [{}, {}, {}],"
                + " \"attachments\": [{}], \"references\": [{}, {}],"
                + " \"parts\": [{\"geometry\": {\"type\": \"FeatureCollection\"}}, {\"geometry\": null}, {}]}";
        String bare = "{\"id\": \"b\", \"areas\": [{}], \"parts\": [{\"geometry\": {\"type\": \"FeatureCollection\"}}]}";
        Path json = dir.resolve("messages.json");
        Files.writeString(json, "{\"data\": [" + two + ", " + bare + "]}", StandardCharsets.UTF_8);

        assertEquals(Map.of("Message_Area", 3, "Message_Category", 1, "Message_Chart", 3, "Attachment", 1,
                "Reference", 2, "FeatureCollection", 2), SampleTool.counts(json));
    }

    /** pg_dump's output: a comment, a SET, an INSERT with a base and a sample id, one with a literal spanning lines */
    static final String DUMP = "--\n-- Data for Name: CategoryDesc; Type: TABLE DATA; Schema: public; Owner: -\n--\n\n"
            + "SET client_encoding = 'UTF8';\n"
            + "INSERT INTO public.\"CategoryDesc\" (id, lang, name, entity_id) VALUES (1000001, 'da', 'Fyr; 1, 2', 1);\n"
            + "INSERT INTO public.\"CategoryDesc\" (id, lang, name, entity_id) VALUES (1000002, 'en', 'A\n''b'', 3', 1000014);\n"
            + "INSERT INTO public.\"Category_Domain\" (\"Category_id\", domains_id) VALUES (10, 1000002);\n"
            + "INSERT INTO public.\"Chart\" (id, \"chartNumber\") VALUES (7, '101');\n";

    static final Map<String, Map<String, String>> COLUMNS = Map.of(
            "CategoryDesc", Map.of("entity_id", "Category"),
            "Category_Domain", Map.of("Category_id", "Category", "domains_id", "Domain"));

    static Map<String, Map<Long, SampleReferences.Reference>> keys() {
        return Map.of("Category", Map.of(
                1L, new SampleReferences.Reference(SampleReferences.Shape.CATEGORY_BY_NAME, List.of("Light")),
                10L, new SampleReferences.Reference(SampleReferences.Shape.CATEGORY_BY_NAME, List.of("Light buoy's"))));
    }

    @Test
    void aBaseIdInAForeignKeyColumnIsNamedByItsKeyAndNothingElseChanges() {
        String light = "(SELECT t.id FROM public.\"Category\" t JOIN public.\"CategoryDesc\" d ON d.entity_id = t.id "
                + "AND d.lang = 'en' WHERE t.mrn IS NULL AND d.name = 'Light')";
        String buoy = light.replace("'Light'", "'Light buoy''s'");
        String expected = DUMP.replace("'Fyr; 1, 2', 1);", "'Fyr; 1, 2', " + light + ");")
                .replace("VALUES (10, 1000002)", "VALUES (" + buoy + ", 1000002)");
        assertEquals(expected, SampleTool.references(DUMP, keys(), COLUMNS));
        // A sample id, a column outside the list, a literal and the lines that are no INSERT stay byte for byte
        assertTrue(expected.contains("3', 1000014);\n") && expected.contains("VALUES (7, '101')"), expected);
    }

    @Test
    void aBaseIdWithoutAKeyOrAValueThatIsNoIdFails() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> SampleTool.references(
                DUMP.replace("VALUES (10, 1000002)", "VALUES (12, 1000002)"), keys(), COLUMNS));
        assertTrue(missing.getMessage().contains("\"Category_Domain\".Category_id names the row 12 of \"Category\""),
                missing.getMessage());
        IllegalArgumentException text = assertThrows(IllegalArgumentException.class, () -> SampleTool.references(
                DUMP.replace("VALUES (10, 1000002)", "VALUES ('10', 1000002)"), keys(), COLUMNS));
        assertTrue(text.getMessage().contains("\"Category_Domain\".Category_id holds '10', not an id"),
                text.getMessage());
    }

    @Test
    void theGuardsKeyChecksAreOnePerTableInKeyOrder() throws IOException {
        Path file = dir.resolve("keys.tsv");
        Files.writeString(file, "PromulgationType\t3\ttypeId\taudio\n"
                + "DictionaryEntry\t300\tkey\taton\taton.light\n"
                + "DictionaryEntry\t277\tkey\taton\taton.buoy\n", StandardCharsets.UTF_8);
        String guard = SampleTool.guardKeys(SampleTool.readKeys(file));
        assertEquals("    missing := (SELECT string_agg(k, ', ' ORDER BY k) FROM (VALUES\n"
                + "            ('aton / aton.buoy', (SELECT count(*) FROM public.\"DictionaryEntry\" t JOIN "
                + "public.\"Dictionary\" d ON d.id = t.dictionary_id WHERE d.name = 'aton' AND t.dict_key = 'aton.buoy')),\n"
                + "            ('aton / aton.light', (SELECT count(*) FROM public.\"DictionaryEntry\" t JOIN "
                + "public.\"Dictionary\" d ON d.id = t.dictionary_id WHERE d.name = 'aton' AND t.dict_key = 'aton.light')))"
                + " v(k, n) WHERE n <> 1);\n"
                + "    IF missing IS NOT NULL THEN\n"
                + "        RAISE EXCEPTION 'niord-sample: base rows of \"DictionaryEntry\" that the sample refers to are "
                + "missing or not unique: %; %', missing, unclean;\n"
                + "    END IF;\n"
                + "    missing := (SELECT string_agg(k, ', ' ORDER BY k) FROM (VALUES\n"
                + "            ('audio', (SELECT count(*) FROM public.\"PromulgationType\" t WHERE t.\"typeId\" = 'audio')))"
                + " v(k, n) WHERE n <> 1);\n"
                + "    IF missing IS NOT NULL THEN\n"
                + "        RAISE EXCEPTION 'niord-sample: base rows of \"PromulgationType\" that the sample refers to are "
                + "missing or not unique: %; %', missing, unclean;\n"
                + "    END IF;\n", guard);
        Files.writeString(file, "Domain\t1\tdomainId\tniord-client-master\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> SampleTool.readKeys(file), "no shape for Domain");
    }

    /** Each shape: its reference reads back as the same key, quotes doubled; a guard-only shape is no reference */
    @Test
    void everyShapeReadsBackAsItWasWritten() {
        for (SampleReferences.Shape shape : SampleReferences.Shape.values()) {
            List<String> parts = switch (shape) {
                case DICTIONARY, PROMULGATION_TYPE, CATEGORY_BY_MRN, CATEGORY_BY_NAME, PARAM_TYPE -> List.of("it's");
                case DICTIONARY_ENTRY -> List.of("aton", "it's");
                case TEMPLATE_PARAM_OF_CATEGORY, TEMPLATE_PARAM_OF_CATEGORY_BY_MRN, TEMPLATE_PARAM_OF_TYPE ->
                        List.of("it's", "12", "aton");
            };
            SampleReferences.Reference reference = new SampleReferences.Reference(shape, parts);
            assertTrue(reference.value().contains("'it''s'"), reference.value());
            assertEquals(shape, SampleReferences.shape(shape.table(), shape.code()));
            if (shape.reference()) {
                assertEquals(reference, SampleReferences.parse(reference.value()), shape.name());
                assertEquals(reference.value(), SampleReferences.parse(reference.value()).value(), shape.name());
            } else {
                assertNull(SampleReferences.parse(reference.value()), shape.name());
            }
            assertNull(SampleReferences.parse(reference.count()), "a count is no reference: " + shape);
        }
        // Not a shape: another table, an added condition
        assertNull(SampleReferences.parse("(SELECT t.id FROM public.\"User\" t WHERE t.username = 'x')"));
        assertNull(SampleReferences.parse("(SELECT t.id FROM public.\"Dictionary\" t WHERE t.name = 'x' OR true)"));
        assertThrows(IllegalArgumentException.class, () -> new SampleReferences.Reference(
                SampleReferences.Shape.TEMPLATE_PARAM_OF_TYPE, List.of("ais", "one", "aton")), "the position is a number");
    }

    Path messages(String attachment) throws IOException {
        Path json = dir.resolve("messages.json");
        Files.writeString(json, "{\"data\": [{\"id\": \"" + UID + "\", \"attachments\": [" + attachment + "]}]}",
                StandardCharsets.UTF_8);
        return json;
    }

    static String attachment(String path) {
        return "{\"type\": \"image/png\", \"path\": \"" + path + "\"}";
    }
}
