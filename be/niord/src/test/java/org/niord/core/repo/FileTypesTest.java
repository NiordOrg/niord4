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

package org.niord.core.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FileTypes}.
 *
 * <p>Post Quarkus&nbsp;&rarr;&nbsp;Spring port, the startup hook is a no-arg {@code @EventListener init()}
 * (was {@code init(@Observes StartupEvent)}). The lookup maps are populated by instantiating {@link FileTypes}
 * directly and calling {@code init()} once in {@link #setUp()}.
 *
 * <p>These tests pin the observable contract of the public methods:
 * <ul>
 *     <li>extension-to-mime-type mapping (case-insensitive),</li>
 *     <li>the {@code null} returned for unknown / extension-less files (an internal NPE is swallowed),</li>
 *     <li>the {@code File} overload delegating to the {@code Path} overload,</li>
 *     <li>{@link FileTypes#isSupportedContentType} for supported / unsupported files, and</li>
 *     <li>the exact icon paths produced by {@code ContentType.getIconPath} (note the trailing literal
 *         {@code _32} in the format string and {@code FILE_TYPE_FOLDER == "img/filetypes"}).</li>
 * </ul>
 */
public class FileTypesTest {

    private FileTypes fileTypes;

    @BeforeEach
    public void setUp() {
        fileTypes = new FileTypes();
        // init() is the no-arg @EventListener startup hook; call once to populate the lookup maps.
        fileTypes.init();
    }

    // ----------------------------------------------------------------------
    // getContentType(Path)
    // ----------------------------------------------------------------------

    @Test
    public void getContentType_pdf() {
        assertEquals("application/pdf", fileTypes.getContentType(Paths.get("a.pdf")));
    }

    @Test
    public void getContentType_png() {
        assertEquals("image/png", fileTypes.getContentType(Paths.get("a.png")));
    }

    @Test
    public void getContentType_doc() {
        assertEquals("application/msword", fileTypes.getContentType(Paths.get("a.doc")));
    }

    @Test
    public void getContentType_extensionIsLowerCased() {
        assertEquals("image/png", fileTypes.getContentType(Paths.get("A.PNG")));
    }

    @Test
    public void getContentType_unknownExtensionReturnsNull() {
        // The lookup map returns null for "xyz" and the chained .getMimeType() NPEs internally,
        // which is caught and reported as an unknown type (null).
        assertNull(fileTypes.getContentType(Paths.get("a.xyz")));
    }

    @Test
    public void getContentType_noExtensionReturnsNull() {
        // No extension -> FilenameUtils.getExtension returns "" -> lookup miss -> caught NPE -> null.
        assertNull(fileTypes.getContentType(Paths.get("README")));
    }

    // ----------------------------------------------------------------------
    // getContentType(File) delegates to the Path overload
    // ----------------------------------------------------------------------

    @Test
    public void getContentType_fileOverloadDelegatesToPath_knownType() {
        assertEquals("application/pdf", fileTypes.getContentType(new File("a.pdf")));
    }

    @Test
    public void getContentType_fileOverloadDelegatesToPath_unknownType() {
        assertNull(fileTypes.getContentType(new File("a.xyz")));
    }

    // ----------------------------------------------------------------------
    // isSupportedContentType(Path)
    // ----------------------------------------------------------------------

    @Test
    public void isSupportedContentType_knownTypeIsTrue() {
        assertTrue(fileTypes.isSupportedContentType(Paths.get("a.pdf")));
    }

    @Test
    public void isSupportedContentType_unknownTypeIsFalse() {
        assertFalse(fileTypes.isSupportedContentType(Paths.get("a.xyz")));
    }

    // ----------------------------------------------------------------------
    // getIcon(Path, IconSize)
    // ----------------------------------------------------------------------

    @Test
    public void getIcon_knownTypeUsesIconNameFolder() {
        // ContentType.getIconPath: "/%s/%s/%s-%d_32.png" with FILE_TYPE_FOLDER=img/filetypes, icon=pdf, size=64.
        assertEquals("/img/filetypes/pdf/pdf-64_32.png",
                fileTypes.getIcon(Paths.get("a.pdf"), IconSize.SIZE_64));
    }

    @Test
    public void getIcon_unknownTypeFallsBackToGenericIcon() {
        // No supported content type -> genericType (icon==null) -> "/%s/%s-%d_32.png" with GENERIC_ICON=generic.
        assertEquals("/img/filetypes/generic-64_32.png",
                fileTypes.getIcon(Paths.get("a.xyz"), IconSize.SIZE_64));
    }

    @Test
    public void getContentType_xlsxIsRecognisedAsOoxmlSpreadsheet() {
        // REPO2: the OOXML spreadsheet extension used to be registered under the transposed typo "xslx",
        // so a real ".xlsx" file was unrecognised (null mime, generic icon). It now resolves correctly.
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                fileTypes.getContentType(Paths.get("book.xlsx")));
    }

    @Test
    public void getIcon_xlsxUsesTheExistingXlsxIconFolder() {
        // REPO2: the icon name was also the "xslx" typo, pointing at a non-existent /img/filetypes/xslx
        // folder; it now points at the real /img/filetypes/xlsx folder that ships in resources.
        assertEquals("/img/filetypes/xlsx/xlsx-64_32.png",
                fileTypes.getIcon(Paths.get("book.xlsx"), IconSize.SIZE_64));
    }
}
