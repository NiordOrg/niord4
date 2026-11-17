/*
 * Copyright 2024 Danish Maritime Authority.
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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure, container-free static helpers in {@link WebUtils}:
 * {@link WebUtils#parseParameterMap(String)}, {@link WebUtils#getParameterValues(Map, String)},
 * {@link WebUtils#encodeURIComponent(String)}, {@link WebUtils#encodeURI(String)} and
 * {@link WebUtils#urlDecode(String)}.
 *
 * The servlet/RESTEasy-dependent methods (getWebAppUrl, getServletUrl, getCookie, getCookieValue,
 * readRequestBody, nocache, cache, getMultipartInputFormParams, getMultipartInputFiles, getFileName)
 * cannot be exercised without a container or a mocking framework and are therefore not covered here.
 */
public class WebUtilsTest {

    // ------------------------------------------------------------------
    // parseParameterMap(String)
    // ------------------------------------------------------------------

    @Test
    public void parseParameterMapCollectsRepeatedKeysInOrder() {
        Map<String, String[]> params = WebUtils.parseParameterMap("a=1&b=2&a=3");
        assertArrayEquals(new String[]{"1", "3"}, params.get("a"));
        assertArrayEquals(new String[]{"2"}, params.get("b"));
        assertEquals(2, params.size());
    }

    @Test
    public void parseParameterMapStripsEverythingUpToQuestionMark() {
        Map<String, String[]> params = WebUtils.parseParameterMap("http://x/y?a=1");
        assertArrayEquals(new String[]{"1"}, params.get("a"));
        assertEquals(1, params.size());
    }

    @Test
    public void parseParameterMapTreatsBareKeyAsEmptyValue() {
        // No '=' in the key/value pair -> value defaults to the empty string.
        Map<String, String[]> params = WebUtils.parseParameterMap("flag");
        assertArrayEquals(new String[]{""}, params.get("flag"));
        assertEquals(1, params.size());
    }

    @Test
    public void parseParameterMapReturnsEmptyMapForEmptyInput() {
        Map<String, String[]> params = WebUtils.parseParameterMap("");
        assertTrue(params.isEmpty());
    }

    @Test
    public void parseParameterMapReturnsEmptyMapForNullInput() {
        // StringUtils.isNotBlank(null) is false, so no parsing occurs.
        Map<String, String[]> params = WebUtils.parseParameterMap(null);
        assertTrue(params.isEmpty());
    }

    @Test
    public void parseParameterMapReturnsEmptyMapForBlankInput() {
        Map<String, String[]> params = WebUtils.parseParameterMap("   ");
        assertTrue(params.isEmpty());
    }

    @Test
    public void parseParameterMapUrlDecodesValues() {
        // "%20" decodes to a space, so the value is " b".
        Map<String, String[]> params = WebUtils.parseParameterMap("a=%20b");
        assertArrayEquals(new String[]{" b"}, params.get("a"));
    }

    @Test
    public void parseParameterMapUrlDecodesKeys() {
        // "a%20b" decodes to "a b" as the map key.
        Map<String, String[]> params = WebUtils.parseParameterMap("a%20b=1");
        assertArrayEquals(new String[]{"1"}, params.get("a b"));
        assertEquals(1, params.size());
    }

    @Test
    public void parseParameterMapHandlesEmptyValueAfterEquals() {
        Map<String, String[]> params = WebUtils.parseParameterMap("a=");
        assertArrayEquals(new String[]{""}, params.get("a"));
    }

    @Test
    public void parseParameterMapKeepsValueContainingEquals() {
        // indexOf("=") only splits at the FIRST '=', so the rest stays in the value.
        Map<String, String[]> params = WebUtils.parseParameterMap("a=1=2");
        assertArrayEquals(new String[]{"1=2"}, params.get("a"));
    }

    // ------------------------------------------------------------------
    // getParameterValues(Map, String)
    // ------------------------------------------------------------------

    @Test
    public void getParameterValuesReturnsFirstValue() {
        Map<String, String[]> map = new HashMap<>();
        map.put("a", new String[]{"first", "second"});
        assertEquals("first", WebUtils.getParameterValues(map, "a"));
    }

    @Test
    public void getParameterValuesReturnsNullForMissingKey() {
        Map<String, String[]> map = new HashMap<>();
        map.put("a", new String[]{"1"});
        assertNull(WebUtils.getParameterValues(map, "missing"));
    }

    @Test
    public void getParameterValuesReturnsNullForNullMap() {
        assertNull(WebUtils.getParameterValues(null, "a"));
    }

    @Test
    public void getParameterValuesReturnsNullForNullName() {
        Map<String, String[]> map = new HashMap<>();
        map.put("a", new String[]{"1"});
        assertNull(WebUtils.getParameterValues(map, null));
    }

    @Test
    public void getParameterValuesReturnsNullForEmptyArray() {
        Map<String, String[]> map = new HashMap<>();
        map.put("a", new String[]{});
        assertNull(WebUtils.getParameterValues(map, "a"));
    }

    @Test
    public void getParameterValuesReturnsNullForNullArrayValue() {
        Map<String, String[]> map = new HashMap<>();
        map.put("a", null);
        assertNull(WebUtils.getParameterValues(map, "a"));
    }

    // ------------------------------------------------------------------
    // encodeURIComponent(String)
    // ------------------------------------------------------------------

    @Test
    public void encodeURIComponentEncodesSpaceAsPercent20() {
        assertEquals("a%20b", WebUtils.encodeURIComponent("a b"));
    }

    @Test
    public void encodeURIComponentPreservesUnreservedJsCharacters() {
        // !'()~ are restored after URLEncoder.encode, mirroring JS encodeURIComponent().
        assertEquals("!'()~", WebUtils.encodeURIComponent("!'()~"));
    }

    @Test
    public void encodeURIComponentEncodesSlash() {
        assertEquals("%2F", WebUtils.encodeURIComponent("/"));
    }

    @Test
    public void encodeURIComponentEncodesColon() {
        assertEquals("%3A", WebUtils.encodeURIComponent(":"));
    }

    @Test
    public void encodeURIComponentEncodesReservedPunctuation() {
        // ';' -> %3B, '?' -> %3F, '&' -> %26, '=' -> %3D
        assertEquals("%3B%3F%26%3D", WebUtils.encodeURIComponent(";?&="));
    }

    @Test
    public void encodeURIComponentLeavesAlphanumericsUnchanged() {
        assertEquals("abcXYZ123", WebUtils.encodeURIComponent("abcXYZ123"));
    }

    @Test
    public void encodeURIComponentEncodesEmptyStringAsEmpty() {
        assertEquals("", WebUtils.encodeURIComponent(""));
    }

    // ------------------------------------------------------------------
    // encodeURI(String)
    // ------------------------------------------------------------------

    @Test
    public void encodeURIRestoresSlash() {
        assertEquals("/", WebUtils.encodeURI("/"));
    }

    @Test
    public void encodeURIRestoresColonSlashSemicolon_andEncodesAQuestionMark() {
        // encodeURI re-decodes %3A %2F %3B so these structural characters survive; a question mark in a name
        // stays encoded, so a file link does not end at it (REPO4)
        assertEquals("a:b/c;d%3Fe", WebUtils.encodeURI("a:b/c;d?e"));
    }

    @Test
    public void encodeURIStillEncodesSpace() {
        assertEquals("a%20b", WebUtils.encodeURI("a b"));
    }

    @Test
    public void encodeURIStillEncodesAmpersandAndEquals() {
        // '&' and '=' are NOT among the characters restored by encodeURI.
        assertEquals("%26%3D", WebUtils.encodeURI("&="));
    }

    // ------------------------------------------------------------------
    // urlDecode(String)
    // ------------------------------------------------------------------

    @Test
    public void urlDecodeDecodesPercentEncodedSpace() {
        assertEquals("a b", WebUtils.urlDecode("a%20b"));
    }

    @Test
    public void urlDecodeDecodesPlusAsSpace() {
        // URLDecoder treats '+' as a space in application/x-www-form-urlencoded form.
        assertEquals("a b", WebUtils.urlDecode("a+b"));
    }

    @Test
    public void urlDecodeReturnsOriginalForInvalidSequence() {
        // A lone '%' is an illegal escape; the exception is swallowed and the input returned verbatim.
        assertEquals("%", WebUtils.urlDecode("%"));
    }

    @Test
    public void urlDecodeLeavesPlainStringUnchanged() {
        assertEquals("plain", WebUtils.urlDecode("plain"));
    }

    @Test
    public void urlDecodeRoundTripsEncodeURIComponent() {
        // Decoding an encodeURIComponent result recovers the original (space path).
        assertEquals("a b", WebUtils.urlDecode(WebUtils.encodeURIComponent("a b")));
    }
}
