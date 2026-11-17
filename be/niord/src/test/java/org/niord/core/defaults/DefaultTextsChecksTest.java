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
package org.niord.core.defaults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.defaults.DefaultSettingsChecks.BASE_SETTINGS;
import static org.niord.core.defaults.DefaultSettingsChecks.COUNTRY_SETTINGS;

/**
 * Each rule of the text checks ({@link DefaultTextsChecks}) fails on a text that breaks it, naming the file, the key
 * and the rule. Each class-path root is a {@code @TempDir} folder (or a jar in one) behind a class loader with no
 * parent, so only the files a test writes exist.
 */
class DefaultTextsChecksTest {

    private static final String BASE_WEB = "defaults/base/texts/en/web.properties";
    private static final String BASE_MESSAGE = "defaults/base/texts/en/message.properties";
    private static final String PACK_DA_WEB = "defaults/packs/da/web.properties";
    private static final String PACK_DA_MESSAGE = "defaults/packs/da/message.properties";
    private static final String COUNTRY_DA_WEB = "defaults/country/texts/da/web.properties";
    private static final String COUNTRY_EN_WEB = "defaults/country/texts/en/web.properties";

    @TempDir
    Path root;

    @TempDir
    Path other;

    // ------------------------------------------------------------------------------------------- //
    // The rules on every text
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aFileThatIsNotValidUtf8Fails() throws IOException {
        byte[] latin1 = "ok = fine\nbad = café\n".getBytes(StandardCharsets.ISO_8859_1);
        Path file = root.resolve(BASE_WEB);
        Files.createDirectories(file.getParent());
        Files.write(file, latin1);

        assertEquals(List.of(BASE_WEB + " bad utf8"), rows(checkTexts(root)));
    }

    @Test
    void aValueOfMoreThan1000CharactersFails() throws IOException {
        write(root, BASE_WEB, "fits = " + "x".repeat(1000) + "\nlong = " + "x".repeat(1001) + "\n");

        assertEquals(List.of(BASE_WEB + " long length"), rows(checkTexts(root)));
    }

    @Test
    void aKeyOfMoreThan255CharactersFails() throws IOException {
        String key = "k".repeat(256);
        write(root, BASE_WEB, "k".repeat(255) + " = fits\n" + key + " = too long a key\n");

        assertEquals(List.of(BASE_WEB + " " + key + " key-length"), rows(checkTexts(root)));
    }

    @Test
    void aControlCharacterFailsWrittenAsItIsOrAsAnEscape() throws IOException {
        write(root, BASE_WEB, "raw = bell\u0007\nescaped = bell\\u0007\ntab = a\\tb\nnewline = a\\nb\n");

        assertEquals(List.of(BASE_WEB + " raw control-character", BASE_WEB + " escaped control-character"),
                rows(checkTexts(root)));
    }

    @Test
    void aMalformedMustachePlaceholderFails() throws IOException {
        write(root, BASE_WEB, "good = Hello {{ name }} and {{other.name}}\nbad = Hello {{name}\n");

        assertEquals(List.of(BASE_WEB + " bad placeholder"), rows(checkTexts(root)));
    }

    @Test
    void anUnescapedApostropheInAValueWithMessageFormatPlaceholdersFails() throws IOException {
        write(root, BASE_MESSAGE, "good = Don''t show {0}\nplain = Don't, without a placeholder\n"
                + "bad = Don't show {0}\nbad2 = {0} o'clock\n");

        assertEquals(List.of(BASE_MESSAGE + " bad placeholder", BASE_MESSAGE + " bad2 placeholder"),
                rows(checkTexts(root)));
    }

    // ------------------------------------------------------------------------------------------- //
    // The rules on a country's or a pack's text against its reference
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPackValueThatDropsAMessageFormatPlaceholderFails() throws IOException {
        write(root, BASE_MESSAGE, "msg.cancel = Cancellation of {0}\nmsg.kept = {0} and {1}\n");
        write(root, PACK_DA_MESSAGE, "msg.cancel = Annullering\nmsg.kept = {1} og {0}\n");

        assertEquals(List.of(PACK_DA_MESSAGE + " msg.cancel placeholder"), rows(checkTexts(root)));
    }

    @Test
    void aCountryValueThatRenamesAMustachePlaceholderFails() throws IOException {
        write(root, BASE_WEB, "tag.label = Tag {{tag}}\n");
        write(root, COUNTRY_DA_WEB, "tag.label = Mærke {{label}}\n");

        assertEquals(List.of(COUNTRY_DA_WEB + " tag.label placeholder"), rows(checkTexts(root)));
    }

    @Test
    void markupWhereEnglishHasNoneFails() throws IOException {
        write(root, BASE_WEB, "plain = Plain text\n");
        write(root, PACK_DA_WEB, "plain = <b>Ren</b> tekst\n");

        assertEquals(List.of(PACK_DA_WEB + " plain markup"), rows(checkTexts(root)));
    }

    @Test
    void aDifferentTagFails() throws IOException {
        write(root, BASE_WEB, "strong = <b>Bold</b>\n");
        write(root, PACK_DA_WEB, "strong = <i>Fed</i>\n");

        assertEquals(List.of(PACK_DA_WEB + " strong markup"), rows(checkTexts(root)));
    }

    @Test
    void aDifferentAttributeFails() throws IOException {
        write(root, BASE_WEB, "styled = <span class=\"note\">Note</span>\n");
        write(root, PACK_DA_WEB, "styled = <span title=\"note\">Bemærk</span>\n");

        assertEquals(List.of(PACK_DA_WEB + " styled markup"), rows(checkTexts(root)));
    }

    @Test
    void aDifferentHrefFails() throws IOException {
        write(root, BASE_WEB, "help = See <a href=\"/help\">help</a>\n");
        write(root, COUNTRY_DA_WEB, "help = Se <a href=\"https://example.com/help\">hjælp</a>\n");

        assertEquals(List.of(COUNTRY_DA_WEB + " help markup"), rows(checkTexts(root)));
    }

    @Test
    void aCountrysOwnKeyIsCheckedAgainstTheCountrysEnglish() throws IOException {
        write(root, BASE_WEB, "a = A\n");
        write(root, COUNTRY_EN_WEB, "dk.own = See <a href=\"/dk\">this</a>\n");
        write(root, COUNTRY_DA_WEB, "dk.own = Se <a href=\"/other\">dette</a>\n");

        assertEquals(List.of(COUNTRY_DA_WEB + " dk.own markup"), rows(checkTexts(root)));
    }

    @Test
    void equalMarkupAndPlaceholdersPass() throws IOException {
        write(root, BASE_WEB, "help = See <a href=\"/help\" class=\"link\">help</a> for {{ topic }}\n"
                + "br = One<br>Two\n");
        write(root, BASE_MESSAGE, "msg.cancel = Cancellation of {0} at {1}\n");
        write(root, PACK_DA_WEB, "help = Se <a class=\"link\" href=\"/help\">hjælp</a> om {{topic}}\nbr = Én<br/>To\n");
        write(root, PACK_DA_MESSAGE, "msg.cancel = Annullering af {0} kl. {1}\n");
        write(root, COUNTRY_DA_WEB, "help = Se <a href=\"/help\" class=\"link\">vejledningen</a> om {{topic}}\n"
                + "dk.own = Uden reference\n");

        assertEquals(List.of(), rows(checkTexts(root)));
    }

    @Test
    void languagesAreFoundInAJar() throws IOException {
        write(root, BASE_WEB, "strong = <b>Bold</b>\n");
        Path jar = other.resolve("country.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String folder : List.of("defaults/", "defaults/packs/", "defaults/packs/da/")) {
                out.putNextEntry(new JarEntry(folder));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(PACK_DA_WEB));
            out.write("strong = <i>Fed</i>\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (URLClassLoader cl = new URLClassLoader(new URL[] {root.toUri().toURL(), jar.toUri().toURL()}, null)) {
            assertEquals(Set.of("da"), DefaultTextsChecks.languages(cl, DefaultTextsChecks.PACKS_FOLDER));
            assertEquals(List.of(PACK_DA_WEB + " strong markup"), rows(DefaultTextsChecks.checkTexts(cl)));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The rule on root-level bundles
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aRootLevelBundleOfAFolderLanguageFails() throws IOException {
        write(root, BASE_WEB, "a = A\n");
        write(root, PACK_DA_WEB, "a = A\n");
        write(root, "web_da.properties", "a = A\n");
        write(root, "mail_en.properties", "a = A\n");

        List<DefaultTextsChecks.Finding> findings = checkTexts(root);

        assertEquals(List.of("web_da.properties root-bundle", "mail_en.properties root-bundle"), rootRows(findings));
        assertTrue(findings.getFirst().file().startsWith("file:"), findings.getFirst().file());
        assertTrue(findings.getFirst().detail().contains("defaults/country/texts/da/web.properties"),
                findings.getFirst().detail());
    }

    @Test
    void aRootLevelBundleOfALanguageNamedOnlyInModelLanguagesFails() throws IOException {
        write(root, BASE_WEB, "a = A\n");
        write(root, COUNTRY_SETTINGS, "{ \"values\": { \"modelLanguages\": [ \"fo\", \"en\" ] } }\n");
        write(root, "pdf_fo.properties", "a = A\n");

        assertEquals(List.of("pdf_fo.properties root-bundle"), rootRows(checkTexts(root)));
    }

    @Test
    void theBaseProductsModelLanguagesCountWhenTheCountrysFileDoesNotSetThem() throws IOException {
        write(root, BASE_WEB, "a = A\n");
        write(root, COUNTRY_SETTINGS, "{ \"values\": { \"country\": \"XX\" } }\n");
        write(root, BASE_SETTINGS, "{ \"values\": { \"modelLanguages\": [ \"kl\", \"en\" ] } }\n");
        write(root, "template_kl.properties", "a = A\n");

        assertEquals(List.of("template_kl.properties root-bundle"), rootRows(checkTexts(root)));
    }

    /** Only the five dictionaries' names in the bounded languages are looked up: the class path is never scanned */
    @Test
    void noRootBundleIsReportedWhenCleanOrOutsideTheBoundedLanguages() throws IOException {
        write(root, BASE_WEB, "a = A\n");
        write(root, PACK_DA_WEB, "a = A\n");
        write(root, COUNTRY_SETTINGS, "{ \"values\": { \"modelLanguages\": [ \"da\", \"en\" ] } }\n");
        write(root, "web.properties", "a = A\n");
        write(root, "fm-test-messages_en.properties", "a = A\n");
        write(root, "web_sv.properties", "a = A\n");

        assertEquals(List.of(), rows(checkTexts(root)));
    }

    // ------------------------------------------------------------------------------------------- //
    // The rules on the base product as a whole
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aTemplateThatUsesAMissingKeyFails() throws IOException {
        write(root, "templates/messages/report.ftl", "${text('present.key')}\n${text(\"missing.key\", 1)}\n"
                + "${text(\"msg.status.\" + msg.status)}\n");

        List<DefaultTextsChecks.Finding> findings = DefaultTextsChecks.checkTemplates(root.resolve("templates"),
                "templates", Set.of("present.key"));

        assertEquals(List.of("templates/messages/report.ftl missing.key template-key"), rows(findings));
        assertEquals("line 2: no such key in defaults/base/texts/en/", findings.getFirst().detail());
    }

    /** The bracket form, as fa-list-pdf.ftl reads pdf.fa.subtitle, is checked too */
    @Test
    void aTemplateThatUsesAMissingKeyInTheBracketFormFails() throws IOException {
        write(root, "templates/messages/list.ftl", "${text['present.key']}\n${text[\"missing.key\"]}\n");

        List<DefaultTextsChecks.Finding> findings = DefaultTextsChecks.checkTemplates(root.resolve("templates"),
                "templates", Set.of("present.key"));

        assertEquals(List.of("templates/messages/list.ftl missing.key template-key"), rows(findings));
        assertEquals("line 2: no such key in defaults/base/texts/en/", findings.getFirst().detail());
    }

    @Test
    void aStatusWithoutItsTextFails() throws IOException {
        write(root, BASE_WEB, "lang.en = English\n");
        write(root, BASE_MESSAGE, runtimeKeys().filter(k -> !k.equals("msg.status.DRAFT"))
                .map(k -> k + " = " + k + "\n").collect(Collectors.joining()));

        assertEquals(List.of(BASE_MESSAGE + " msg.status.DRAFT runtime-prefix"), rows(checkBaseKeys(root)));
    }

    @Test
    void theBaseProductNamingAnotherLanguageFails() throws IOException {
        write(root, BASE_WEB, "lang.en = English\nlang.da = Danish\n");
        write(root, BASE_MESSAGE, runtimeKeys().map(k -> k + " = " + k + "\n").collect(Collectors.joining()));

        assertEquals(List.of(BASE_WEB + " lang.da language-name"), rows(checkBaseKeys(root)));
    }

    @Test
    void theBaseProductWithoutEnglishsNameFails() throws IOException {
        write(root, BASE_WEB, "other = Other\n");
        write(root, BASE_MESSAGE, runtimeKeys().map(k -> k + " = " + k + "\n").collect(Collectors.joining()));

        assertEquals(List.of(BASE_WEB + " lang.en language-name"), rows(checkBaseKeys(root)));
    }

    /** Every key a template builds at runtime from an enum */
    private static Stream<String> runtimeKeys() {
        return Stream.of(
                Stream.of(Status.values()).map(s -> "msg.status." + s.name()),
                Stream.of(Type.values()).map(t -> "msg.type." + t.name()),
                Stream.of(MessagePartType.values()).map(p -> "msg.field." + p.name().toLowerCase()))
                .flatMap(s -> s);
    }

    private static List<DefaultTextsChecks.Finding> checkTexts(Path root) throws IOException {
        try (URLClassLoader cl = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            return DefaultTextsChecks.checkTexts(cl);
        }
    }

    private static List<DefaultTextsChecks.Finding> checkBaseKeys(Path root) throws IOException {
        try (URLClassLoader cl = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            return DefaultTextsChecks.checkBaseKeys(cl);
        }
    }

    /** The findings as "file key rule" */
    private static List<String> rows(List<DefaultTextsChecks.Finding> findings) {
        return findings.stream().map(f -> f.file() + " " + f.key() + " " + f.rule()).toList();
    }

    /** The findings as "file-name rule", the file being a URL */
    private static List<String> rootRows(List<DefaultTextsChecks.Finding> findings) {
        return findings.stream().map(f -> f.file().substring(f.file().lastIndexOf('/') + 1) + " " + f.rule())
                .toList();
    }

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
