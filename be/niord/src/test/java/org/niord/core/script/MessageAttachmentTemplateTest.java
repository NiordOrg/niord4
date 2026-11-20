/*
 * Copyright 2026 Danish Emergency Management Agency.
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
package org.niord.core.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.DomainService;
import org.niord.web.map.MapImageCredit;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REPO5: the address a message PDF fetches an image attachment from. The PDF renderer asks the backend for
 * {@code /rest/repo/file/<path>}, and the base product's {@code message-support.ftl} wrote the attachment's path
 * there as stored. A path stored as the file was named (an imported message's) then put a raw {@code %} (an invalid
 * escape), a raw {@code ;} (a path parameter, which the request firewall refuses) or a raw space into the address,
 * and the image was missing from the PDF. The template now encodes each segment and keeps the escapes a path stored
 * encoded already holds, so both spellings give the one address the admin app and the public site ask for.
 *
 * <p>Rendered through the real builder and the base product's templates, as {@link FmTemplateBuilderTimeZoneTest}
 * does, with a one-line template that calls the macro.
 */
class MessageAttachmentTemplateTest {

    private static final String TEMPLATE = "templates/it/attachment-test.ftl";

    /** The address the file named "50% off;v2 chart.png" is served at */
    private static final String ADDRESS = "/rest/repo/file/messages/a/1f/uid-1/1/50%25%20off%3Bv2%20chart.png";

    private FmTemplateService templateService;

    /** The dictionary: every key answers with itself */
    private static class Texts extends ResourceBundle {
        @Override
        protected Object handleGetObject(String key) {
            return key;
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.emptyEnumeration();
        }
    }

    @BeforeEach
    void setUp() {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage(anyString())).thenReturn("en");
        when(app.getLanguages(anyString())).thenReturn(new String[] { "en" });
        when(app.getLocale(any())).thenReturn(Locale.ENGLISH);

        DictionaryService dictionaryService = mock(DictionaryService.class);
        when(dictionaryService.getDictionariesAsResourceBundle(any(), anyString())).thenReturn(new Texts());

        ScriptResourceService resources = mock(ScriptResourceService.class);
        Map<String, ScriptResource> rows = BootstrapTemplates.rows();
        rows.put(TEMPLATE, BootstrapTemplates.row(TEMPLATE,
                "<#include \"/templates/messages/message-support.ftl\"/><@renderAttachment att=att/>"));
        BootstrapTemplates.stub(resources, rows);

        templateService = new FmTemplateService(dictionaryService, mock(DomainService.class), resources, app,
                new MapImageCredit());
    }

    /** The image address the attachment renders with */
    private String imageAddress(String path) throws Exception {
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("path", path);
        att.put("type", "image/png");
        String html = templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .data("att", att)
                .language("en")
                .process();
        int src = html.indexOf("src=\"");
        return html.substring(src + 5, html.indexOf('"', src + 5));
    }

    /** FAILS on the template that wrote the stored path as it is. */
    @Test
    void aNameWithAPercentASemicolonAndASpaceStoredAsNamedIsEncoded() throws Exception {
        assertEquals(ADDRESS, imageAddress("messages/a/1f/uid-1/1/50% off;v2 chart.png"));
    }

    /** An upload stores the name encoded already, and its escapes are kept, not encoded a second time. */
    @Test
    void aPathStoredEncodedKeepsItsAddress() throws Exception {
        assertEquals(ADDRESS, imageAddress("messages/a/1f/uid-1/1/50%25%20off%3Bv2%20chart.png"));
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/Buoy_seal.jpg",
                imageAddress("messages/a/1f/uid-1/1/Buoy_seal.jpg"));
    }

    /** Letters outside ASCII are encoded as UTF-8, as the admin app encodes them. */
    @Test
    void aNameOutsideAsciiIsEncodedAsUtf8() throws Exception {
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/%C3%98resund.png",
                imageAddress("messages/a/1f/uid-1/1/Øresund.png"));
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/%C3%98resund.png",
                imageAddress("messages/a/1f/uid-1/1/%C3%98resund.png"));
    }

    /**
     * A name with a "%" that starts no escape was stored as it was named, so the escape-like text beside it is
     * part of the name and is encoded too, as encodeRepoPath encodes it: "50% off%20v2.png" is the file of that
     * name, not "50% off v2.png". FAILS on the template that kept every escape-like triple.
     */
    @Test
    void aNameWithAStrayPercentIsEncodedWholeEscapesIncluded() throws Exception {
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/50%25%20off%2520v2.png",
                imageAddress("messages/a/1f/uid-1/1/50% off%20v2.png"));
    }

    /**
     * Escapes that are not a UTF-8 character do not decode, so the name was stored as it was named and its "%"
     * is a percent sign: "%FF.png" and the overlong "%C0%AF.png" are encoded whole, as encodeRepoPath encodes
     * them. FAILS on the template that kept every escape-like triple.
     */
    @Test
    void escapesThatAreNotUtf8AreANameAsUploaded() throws Exception {
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/%25FF.png",
                imageAddress("messages/a/1f/uid-1/1/%FF.png"));
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/%25C0%25AF.png",
                imageAddress("messages/a/1f/uid-1/1/%C0%AF.png"));
        assertEquals("/rest/repo/file/messages/a/1f/uid-1/1/%25ED%25A0%2580.png",
                imageAddress("messages/a/1f/uid-1/1/%ED%A0%80.png"));
    }
}
