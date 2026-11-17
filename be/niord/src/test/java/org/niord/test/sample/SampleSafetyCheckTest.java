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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample's safety rules (docs/fe-country-split-plan.md P9): a clean snippet passes, and each rule is red on a
 * snippet of its own.
 */
class SampleSafetyCheckTest {

    static final String SENTINEL = SampleSafetyCheck.SENTINEL_PREFIX + "0123456789abcdef0123456789abcdef\n";

    /** The guard, as the regeneration writes it: the first statement after BEGIN */
    static final String GUARD = "DO $guard$\n"
            + "DECLARE\n"
            + "    stale CONSTANT text := 'regenerate the sample with be/niord/scripts/regen-sample.sh';\n"
            + "    missing text;\n"
            + "BEGIN\n"
            + "    IF current_database() !~ '^niord_sample(_[a-z]{2})?$' THEN\n"
            + "        RAISE EXCEPTION 'niord-sample: the sample is loaded only into niord_sample or niord_sample_<cc>, "
            + "not %', current_database();\n"
            + "    END IF;\n"
            + "    missing := (SELECT string_agg(k, ', ' ORDER BY k) FROM (VALUES\n"
            + "            ('aton', (SELECT count(*) FROM public.\"Dictionary\" t WHERE t.name = 'aton'))) v(k, n) "
            + "WHERE n <> 1);\n"
            + "    IF missing IS NOT NULL THEN\n"
            + "        RAISE EXCEPTION 'niord-sample: base rows of \"Dictionary\" that the sample refers to are missing or "
            + "not unique: %; %', missing, stale;\n"
            + "    END IF;\n"
            + "END\n"
            + "$guard$;\n";

    /** Every statement kind the regeneration writes */
    static final String CLEAN = SENTINEL
            + "\n\\set ON_ERROR_STOP on\n\n"
            + "BEGIN;\n\n"
            + "-- The guard\n"
            + GUARD
            + "SET statement_timeout = 0;\n"
            + "SET client_encoding = 'UTF8';\n"
            + "SET SESSION AUTHORIZATION DEFAULT;\n"
            + "ALTER TABLE public.\"Domain\" DISABLE TRIGGER ALL;\n"
            + "INSERT INTO public.\"Domain\" (id, active, \"domainId\", name) VALUES (101, true, 'niord-nw', 'NW');\n"
            + "ALTER TABLE public.\"Domain\" ENABLE TRIGGER ALL;\n"
            + "INSERT INTO public.\"MessageDesc\" (id, lang, title, vicinity, entity_id) VALUES "
            + "(201, 'en', 'SAMPLE — Kattegat. It''s a test, ask sample@example.org.', 'Kattegat', 301);\n"
            + "INSERT INTO public.\"FeatureCollection\" (id, uid) VALUES (401, '00451234-5678-4000-8000-000000000001');\n"
            + "INSERT INTO public.\"MessagePartDesc\" (id, details) VALUES (501, '<p>Pos. 55° 42,0''N - 012° 39,6''E, "
            + "kl. 0700 til 1600, 12:00-16:00, 2026-10-22 00:00:00, coordinate 56.004560876000028.</p>');\n"
            + "SELECT pg_catalog.setval('public.\"Domain_SEQ\"', greatest((SELECT last_value FROM public.\"Domain_SEQ\"), "
            + "(SELECT max(id) FROM public.\"Domain\")));\n"
            + "\n"
            + "COMMIT;\n";

    @Test
    void aCleanSnippetPasses() {
        assertEquals(List.of(), SampleSafetyCheck.checkSql(CLEAN));
    }

    @Test
    void anAddressOutsideExampleOrgIsRed() {
        assertRed("rule (b) e-mail", CLEAN.replace("sample@example.org", "sample@example.com"));
    }

    @Test
    void aPhoneNumberIsRed() {
        assertRed("rule (c) phone", CLEAN.replace("ask sample@example.org", "call +45 12 34 56 78"));
        assertRed("rule (c) phone", CLEAN.replace("ask sample@example.org", "call 12 34 56 78"));
        assertRed("rule (c) phone", CLEAN.replace("ask sample@example.org", "tlf. 1234"));
    }

    /** The plain Danish forms, also with a non-breaking space between the groups */
    @Test
    void aPlainDanishPhoneNumberIsRed() {
        for (String number : List.of("12345678", "12-34-56-78", "12.34.56.78", "12&nbsp;34&nbsp;56&nbsp;78",
                "12 34 56 78")) {
            assertRed("rule (c) phone", CLEAN.replace("ask sample@example.org", "call " + number));
        }
    }

    @Test
    void anAddressWithALetterOutsideAsciiIsRed() {
        assertRed("rule (b) e-mail", CLEAN.replace("sample@example.org", "x@søfart.dk"));
    }

    @Test
    void aForbiddenOrUnknownTableIsRed() {
        assertRed("rule (a) table: INSERT into \"Contact\"",
                CLEAN.replace("COMMIT;", "INSERT INTO public.\"Contact\" (id, email) VALUES (1, 'a@example.org');\nCOMMIT;"));
        assertRed("rule (a) table: INSERT into \"Lighthouse\"",
                CLEAN.replace("COMMIT;", "INSERT INTO public.\"Lighthouse\" (id) VALUES (1);\nCOMMIT;"));
    }

    /** Rule (a) is closed over statement kinds: anything the regeneration does not write is red */
    @Test
    void aStatementTheRegenerationDoesNotWriteIsRed() {
        for (String statement : List.of(
                "UPDATE public.\"User\" SET email = 'a@example.org' WHERE id = 1;",
                "DELETE FROM public.\"Message\";",
                "COPY public.\"Contact\" (id, email) FROM stdin;",
                "TRUNCATE public.\"Message\";",
                "DO $$ BEGIN INSERT INTO public.\"User\" (id) VALUES (1); END $$;",
                "DO $guard$ BEGIN NULL; END $guard$;",
                "SELECT pg_catalog.set_config('search_path', '', false);",
                "ALTER TABLE public.\"User\" DISABLE TRIGGER ALL;",
                "SELECT pg_catalog.setval('public.\"User_SEQ\"', 1000);",
                "\\! echo a psql meta-command")) {
            assertRed("rule (a) statement", CLEAN.replace("COMMIT;", statement + "\nCOMMIT;"));
        }
    }

    @Test
    void aWriteInsideTheGuardIsRed() {
        assertRed("rule (a) statement", CLEAN.replace("    END IF;\nEND\n$guard$",
                "    END IF;\n    INSERT INTO public.\"User\" (id) VALUES (1);\nEND\n$guard$"));
        assertRed("rule (a) statement", CLEAN.replace("    END IF;\nEND\n$guard$",
                "    END IF;\n    EXECUTE 'DELETE FROM public.\"Message\"';\nEND\n$guard$"));
    }

    @Test
    void theGuardOnlyAsTheFirstStatementAfterBegin() {
        assertRed("rule (a) statement", CLEAN.replace(GUARD, "").replace("COMMIT;", GUARD + "COMMIT;"));
    }

    /** A base row named by its natural key, in each of SampleReferences' reference shapes, is a value rule (a) admits */
    @Test
    void aForeignKeyNamedByItsNaturalKeyPasses() {
        StringBuilder inserts = new StringBuilder();
        int id = 601;
        for (SampleReferences.Shape shape : SampleReferences.Shape.values()) {
            if (!shape.reference()) {
                continue;
            }
            List<String> parts = shape.table().equals("TemplateParam") ? List.of("Light unlit", "0", "aton")
                    : shape.table().equals("DictionaryEntry") ? List.of("aton", "aton.buoy") : List.of("Light");
            inserts.append("INSERT INTO public.\"CategoryDesc\" (id, lang, name, entity_id) VALUES (").append(id++)
                    .append(", 'da', 'Fyr', ").append(new SampleReferences.Reference(shape, parts).value()).append(");\n");
        }
        assertEquals(List.of(), SampleSafetyCheck.checkSql(CLEAN.replace("COMMIT;", inserts + "COMMIT;")));
    }

    /**
     * Any other expression as a value is red: a subselect of a table outside the five, one of a shape's tables that
     * is not a shape, a function call, an operator
     */
    @Test
    void anExpressionThatIsNoNaturalKeyIsRed() {
        for (String value : List.of("(SELECT id FROM public.\"User\" t WHERE t.username = 'x')",
                "(SELECT t.id FROM public.\"Domain\" t WHERE t.name = 'x')",
                "(SELECT t.id FROM public.\"Dictionary\" t WHERE t.name = 'x' OR true)",
                "pg_read_file('x')",
                "1 + 1")) {
            assertRed("rule (a) value: \"CategoryDesc\".entity_id", CLEAN.replace("COMMIT;",
                    "INSERT INTO public.\"CategoryDesc\" (id, lang, name, entity_id) VALUES (701, 'da', 'Fyr', "
                            + value + ");\nCOMMIT;"));
        }
    }

    /** The two branches that fail closed: a multi-row VALUES, and a tail after it */
    @Test
    void anInsertTheCheckCannotReadIsRed() {
        assertRed("rule (a) table", CLEAN.replace("COMMIT;",
                "INSERT INTO public.\"Domain\" (id, name) VALUES (1, 'a'), (2, 'b');\nCOMMIT;"));
        assertRed("rule (a) table", CLEAN.replace("COMMIT;",
                "INSERT INTO public.\"Domain\" (id, name) VALUES (1, 'a') ON CONFLICT DO NOTHING;\nCOMMIT;"));
        assertRed("rule (a) table", CLEAN.replace("COMMIT;",
                "INSERT INTO public.\"Domain\" (id, name) VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = (1);\nCOMMIT;"));
    }

    /**
     * The parser keeps a statement whole across a literal that holds a semicolon, a newline and an INSERT, a dash
     * pair, a dollar tag and a backslash, reads a cast and a NULL, and skips a comment between statements
     */
    @Test
    void anAdversarialButCleanSnippetPasses() {
        String sql = CLEAN.replace("COMMIT;",
                "INSERT INTO public.\"MessagePartDesc\" (id, details) VALUES (502, '<p>Line one'');\n"
                        + "INSERT INTO public.\"User\" (id) VALUES (1);\n-- not a comment\n"
                        + "\\! not a meta-command $tag$ not a dollar quote $tag$</p>'::text);\n"
                        + "-- a comment between statements; UPDATE public.\"User\" SET id = 2;\n"
                        + "INSERT INTO public.\"MessageDesc\" (id, lang, title, vicinity, entity_id) VALUES "
                        + "(203, 'da', 'SAMPLE — Kattegat; ''quoted''', NULL, 301);\n"
                        + "COMMIT;");
        assertEquals(List.of(), SampleSafetyCheck.checkSql(sql));
        List<String> details = SampleSafetyCheck.statements(sql).stream()
                .filter(st -> st.startsWith("INSERT INTO public.\"MessagePartDesc\"")).toList();
        assertEquals(2, details.size(), details::toString);
        assertTrue(details.get(1).contains("VALUES (1);\n-- not a comment\n\\! not a meta-command"), details::toString);
    }

    @Test
    void aTitleWithoutTheSamplePrefixIsRed() {
        assertRed("rule (d) title", CLEAN.replace("'SAMPLE — Kattegat", "'Kattegat"));
        assertRed("rule (d) title", CLEAN.replace("COMMIT;", "INSERT INTO public.\"MessageDesc\" "
                + "(id, lang, title, vicinity, entity_id) VALUES (202, 'da', NULL, 'Kattegat', 301);\nCOMMIT;"));
    }

    @Test
    void aMissingSentinelIsRed() {
        assertRed("rule (e) sentinel", CLEAN.replace(SENTINEL, ""));
    }

    /** A path of the repository's layout */
    static final String PATH = "messages/5/a3/00112233-4455-4000-8000-000000000001/1/sample.png";

    @Test
    void aPngWithAMetadataChunkIsRed() throws Exception {
        byte[] clean = SampleTool.png(SampleTool.picture(1));
        assertEquals(List.of(), SampleSafetyCheck.checkContent(PATH, clean));

        // A tEXt chunk after IHDR (8 bytes of signature, 25 of IHDR)
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        SampleTool.chunk(text, "tEXt", "Author\0Someone".getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(clean, 0, 33);
        tagged.write(text.toByteArray());
        tagged.write(clean, 33, clean.length - 33);
        List<String> findings = SampleSafetyCheck.checkContent(PATH, tagged.toByteArray());
        assertEquals(1, findings.size(), findings::toString);
        assertTrue(findings.get(0).startsWith("rule (f) metadata: " + PATH + " carries a tEXt"), findings::toString);
    }

    @Test
    void aRepositoryRowThatIsNotAPngIsRedAndReadForAddresses() {
        String notes = "messages/5/a3/00112233-4455-4000-8000-000000000001/1/notes.txt";
        List<String> findings = SampleSafetyCheck.checkContent(notes, "Ask a@dma.dk".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, findings.size(), findings::toString);
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("rule (f) attachment: " + notes)), findings::toString);
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("rule (b) e-mail: " + notes + " names a@dma.dk")),
                findings::toString);

        String photo = "messages/5/a3/00112233-4455-4000-8000-000000000001/1/photo.png";
        findings = SampleSafetyCheck.checkContent(photo,
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0});
        assertEquals(1, findings.size(), findings::toString);
        assertTrue(findings.get(0).startsWith("rule (f) attachment: " + photo + " is not a PNG"), findings::toString);
    }

    @Test
    void aPngWithAnotherChunkOrACorruptLengthIsRed() throws Exception {
        byte[] clean = SampleTool.png(SampleTool.picture(1));

        // An iCCP chunk after IHDR: not named as metadata, but not one of the four chunks SampleTool writes
        ByteArrayOutputStream iccp = new ByteArrayOutputStream();
        SampleTool.chunk(iccp, "iCCP", "profile\0\0".getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(clean, 0, 33);
        tagged.write(iccp.toByteArray());
        tagged.write(clean, 33, clean.length - 33);
        List<String> findings = SampleSafetyCheck.checkContent(PATH, tagged.toByteArray());
        assertEquals(1, findings.size(), findings::toString);
        assertTrue(findings.get(0).startsWith("rule (f) chunks: " + PATH), findings::toString);

        // The IHDR chunk's length near 2^31: reported, never thrown
        byte[] corrupt = clean.clone();
        corrupt[8] = (byte) 0x7f;
        corrupt[9] = (byte) 0xff;
        corrupt[10] = (byte) 0xff;
        corrupt[11] = (byte) 0xf8;
        findings = SampleSafetyCheck.checkContent(PATH, corrupt);
        assertEquals(1, findings.size(), findings::toString);
        assertTrue(findings.get(0).contains("is not a well-formed PNG"), findings::toString);
    }

    /**
     * A RepoFile row of the SQL is read as the regeneration writes it: its content a bytea literal, checked as the
     * PNG it holds; the digits of its hash are no phone number; a path outside the layout is red
     */
    @Test
    void aRepoFileRowOfTheSqlIsCheckedAsAnAttachment() throws Exception {
        byte[] clean = SampleTool.png(SampleTool.picture(1));
        String hash = "1234567890123456789012345678901234567890123456789012345678901234";
        String row = "INSERT INTO public.\"RepoFile\" (path, folder, name, size, sha256, created, updated, expires, data) "
                + "VALUES ('" + PATH + "', '" + PATH.substring(0, PATH.lastIndexOf('/')) + "', 'sample.png', "
                + clean.length + ", '" + hash + "', '2026-10-22 00:00:00+00', '2026-10-22 00:00:00+00', NULL, '\\x"
                + HexFormat.of().formatHex(clean) + "');\n";
        assertEquals(List.of(), SampleSafetyCheck.checkSql(CLEAN.replace("COMMIT;", row + "COMMIT;")));

        assertRed("rule (f) attachment: publications/sample.png is not a path of the repository's layout",
                CLEAN.replace("COMMIT;", row.replace("'" + PATH + "'", "'publications/sample.png'") + "COMMIT;"));

        String text = row.replace(HexFormat.of().formatHex(clean),
                HexFormat.of().formatHex("Ask a@dma.dk".getBytes(StandardCharsets.UTF_8)));
        List<String> findings = SampleSafetyCheck.checkSql(CLEAN.replace("COMMIT;", text + "COMMIT;"));
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("rule (b) e-mail: " + PATH + " names a@dma.dk")),
                findings::toString);
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("rule (f) attachment: " + PATH + " is not a PNG")),
                findings::toString);
    }

    @Test
    void theAttachmentsAreTheSameBytesOnEveryRun() throws Exception {
        byte[] first = SampleTool.png(SampleTool.picture(2));
        byte[] second = SampleTool.png(SampleTool.picture(2));
        assertArrayEquals(first, second);
        assertEquals(List.of("IHDR", "PLTE", "IDAT", "IEND"), SampleSafetyCheck.pngChunks(first));
        assertTrue(!Arrays.equals(first, SampleTool.png(SampleTool.picture(3))), "each picture differs");
    }

    @Test
    void everyPictureUpToTheLimitIsDrawnInsideTheFrame() throws Exception {
        for (int n = 1; n <= SampleTool.MAX_ATTACHMENTS; n++) {
            assertEquals(List.of("IHDR", "PLTE", "IDAT", "IEND"),
                    SampleSafetyCheck.pngChunks(SampleTool.png(SampleTool.picture(n))), "picture " + n);
        }
    }

    static void assertRed(String rule, String sql) {
        List<String> findings = SampleSafetyCheck.checkSql(sql);
        assertEquals(1, findings.size(), findings::toString);
        assertTrue(findings.get(0).startsWith(rule), findings::toString);
    }
}
