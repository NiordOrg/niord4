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
package org.niord.core.util;

import org.junit.jupiter.api.Test;
import org.niord.core.web.NiordWebException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebUtils#getMultipartFiles}, {@link WebUtils#bareFileName}, {@link WebUtils#isBareFileName} and
 * {@link WebUtils#fileDirectlyIn}: the file name of an uploaded part is the client's choice, and the upload
 * endpoints resolve it against a folder to write the file there. So the map hands them a bare name only, a
 * part whose name leaves nothing usable is refused, and a name resolved against a folder must end directly
 * inside it.
 */
class WebUtilsMultipartFilesTest {

    private static MockMultipartHttpServletRequest request(String... fileNames) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        int i = 0;
        for (String fileName : fileNames) {
            request.addFile(new MockMultipartFile("file" + i++, fileName, "application/json",
                    ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }

    // ------------------------------------------------------------------ getMultipartFiles

    @Test
    void aPlainFileNameIsKeptAndTheContentIsReadable() throws Exception {
        Map<String, InputStream> files = WebUtils.getMultipartFiles(request("charts.json"));

        assertEquals(Set.of("charts.json"), files.keySet());
        assertEquals("content of charts.json",
                new String(files.get("charts.json").readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aParentRelativeFileNameIsReducedToItsLastSegment() throws Exception {
        Map<String, InputStream> files = WebUtils.getMultipartFiles(
                request("../../../../other-job/in/x.json"));

        assertEquals(Set.of("x.json"), files.keySet(),
                "no directory part of the submitted name may reach the caller");
    }

    @Test
    void anAbsoluteOrWindowsPathIsReducedToItsLastSegment() throws Exception {
        assertEquals(Set.of("passwd"), WebUtils.getMultipartFiles(request("/etc/passwd")).keySet());
        assertEquals(Set.of("areas.json"),
                WebUtils.getMultipartFiles(request("C:\\Users\\someone\\areas.json")).keySet());
        assertEquals(Set.of("b.json"),
                WebUtils.getMultipartFiles(request("..\\..\\a\\b.json")).keySet());
    }

    @Test
    void aNameThatLeavesNothingUsableIsRefusedWith400() {
        for (String bad : new String[] { "", "   ", ".", "..", "../..", "a/", "a\\..", "/" }) {
            NiordWebException e = assertThrows(NiordWebException.class,
                    () -> WebUtils.getMultipartFiles(request(bad)),
                    "a part named '" + bad + "' must be refused");
            assertEquals(400, e.getStatusCode().value());
        }
    }

    @Test
    void aRequestWithoutFilePartsGivesAnEmptyMap() throws Exception {
        assertTrue(WebUtils.getMultipartFiles(new MockMultipartHttpServletRequest()).isEmpty());
    }

    // ------------------------------------------------------------------ bareFileName / isBareFileName

    @Test
    void bareFileNameStripsEveryDirectoryPartAndTrims() {
        assertEquals("x.json", WebUtils.bareFileName("x.json"));
        assertEquals("x.json", WebUtils.bareFileName("  x.json  "));
        assertEquals("x.json", WebUtils.bareFileName("a/b/x.json"));
        assertEquals("x.json", WebUtils.bareFileName("a\\b\\x.json"));
        assertEquals("..x", WebUtils.bareFileName("..x"), "dots inside a name are part of the name");
        assertNull(WebUtils.bareFileName(null));
        assertNull(WebUtils.bareFileName(".."));
        assertNull(WebUtils.bareFileName("a/."));
    }

    @Test
    void isBareFileNameAcceptsOnlyANameWithoutDirectoryParts() {
        assertTrue(WebUtils.isBareFileName("Buoy seal (1).jpg"));
        assertTrue(WebUtils.isBareFileName(" padded.png "), "surrounding white space is not a directory part");
        assertFalse(WebUtils.isBareFileName("../x.png"));
        assertFalse(WebUtils.isBareFileName("a/x.png"));
        assertFalse(WebUtils.isBareFileName("a\\x.png"));
        assertFalse(WebUtils.isBareFileName("/x.png"));
        assertFalse(WebUtils.isBareFileName(".."));
        assertFalse(WebUtils.isBareFileName("."));
        assertFalse(WebUtils.isBareFileName(""));
        assertFalse(WebUtils.isBareFileName(null));
    }

    // ------------------------------------------------------------------ fileDirectlyIn

    /** A folder as the callers pass it: absolute and normalised */
    private static final Path FOLDER = Paths.get("repo", "temp", "edit-folder").toAbsolutePath().normalize();

    @Test
    void fileDirectlyInResolvesANameDirectlyInsideTheFolder() {
        assertEquals(FOLDER.resolve("notice.pdf"), WebUtils.fileDirectlyIn(FOLDER, "notice.pdf"));
        assertEquals(FOLDER.resolve("Søkort (2).json"), WebUtils.fileDirectlyIn(FOLDER, "Søkort (2).json"));
        assertEquals(FOLDER.resolve("x.json"), WebUtils.fileDirectlyIn(FOLDER, "sub/../x.json"),
                "a name that only passes through a sub-folder ends directly inside the folder");
        assertEquals(FOLDER.resolve("x.json"),
                WebUtils.fileDirectlyIn(Paths.get("repo", "temp", ".", "edit-folder"), "x.json").toAbsolutePath(),
                "the folder is compared normalised");
    }

    @Test
    void fileDirectlyInRefusesEverythingElse() {
        String absolute = FOLDER.getRoot().resolve("x.json").toString();
        for (String name : new String[] {
                "../other-folder/x.json", "../x.json", "sub/../../x.json", "sub/x.json", absolute, "", ".", ".." }) {
            assertNull(WebUtils.fileDirectlyIn(FOLDER, name), "'" + name + "' must be refused");
        }
        assertNull(WebUtils.fileDirectlyIn(FOLDER, null));
    }
}
