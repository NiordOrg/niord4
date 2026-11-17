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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Additional unit tests for the pure JavaScript-emulating encode/decode helpers in {@link WebUtils}:
 * {@link WebUtils#encodeURIComponent(String)}, {@link WebUtils#encodeURI(String)} and
 * {@link WebUtils#urlDecode(String)}.
 *
 * <p>The sibling {@link WebUtilsTest} already covers the common happy paths (space encoding, the
 * {@code !'()~} restoration, slash/colon, a handful of reserved punctuation marks, plain/round-trip
 * decode). This class deliberately avoids duplicating those and instead pins the genuinely-uncovered
 * contract edges:
 * <ul>
 *     <li>the full JavaScript "unreserved" survivor set ({@code * - _ .}) that {@code URLEncoder}
 *         already leaves untouched;</li>
 *     <li>multi-byte UTF-8 characters expanding to several {@code %XX} octets;</li>
 *     <li>where this implementation <em>diverges</em> from a faithful JS {@code encodeURI()}
 *         (it only restores {@code : / ;}, so {@code @ , + $ #} stay percent-encoded, and it encodes
 *         {@code ?} and {@code '} as well, REPO4);</li>
 *     <li>the asymmetric null handling of the two encode/decode entry points;</li>
 *     <li>the {@code try/catch} fallback of {@link WebUtils#urlDecode(String)} for assorted
 *         malformed {@code %xx} sequences;</li>
 *     <li>reserved-character round-trips through encode then decode.</li>
 * </ul>
 *
 * These are pure functions with no servlet/RESTEasy dependency, so no container or mocking is needed.
 */
public class WebUtilsEncodingTest {

    // ------------------------------------------------------------------
    // encodeURIComponent(String) - JavaScript unreserved survivor set
    // ------------------------------------------------------------------

    @Test
    public void encodeURIComponentPreservesAsteriskAndDashUnderscoreDot() {
        // JS encodeURIComponent leaves A-Za-z0-9 plus - _ . ! ~ * ' ( ) untouched.
        // URLEncoder already leaves * - _ . alone (they are in its "safe" set), so they survive
        // without any explicit restoration step.
        assertEquals("*-_.", WebUtils.encodeURIComponent("*-_."));
    }

    @Test
    public void encodeURIComponentEncodesAtCommaPlusDollarHash() {
        // None of these are in URLEncoder's safe set nor among the restored characters, so each is
        // percent-encoded: @ -> %40, , -> %2C, + -> %2B, $ -> %24, # -> %23.
        assertEquals("%40%2C%2B%24%23", WebUtils.encodeURIComponent("@,+$#"));
    }

    @Test
    public void encodeURIComponentEncodesLiteralPlusAsPercent2B() {
        // A literal '+' must NOT collapse to a space: URLEncoder emits %2B for it, and the
        // ".replaceAll(\"\\\\+\", \"%20\")" step only rewrites the '+' that URLEncoder used for spaces.
        assertEquals("%2B", WebUtils.encodeURIComponent("+"));
    }

    @Test
    public void encodeURIComponentEncodesLiteralPercentAsPercent25() {
        assertEquals("%25", WebUtils.encodeURIComponent("%"));
    }

    @Test
    public void encodeURIComponentEncodesMultiByteUtf8AsSeveralOctets() {
        // 'æ' is U+00E6 -> 0xC3 0xA6 in UTF-8 and '€' is U+20AC -> 0xE2 0x82 0xAC.
        assertEquals("%C3%A6", WebUtils.encodeURIComponent("æ"));
        assertEquals("%E2%82%AC", WebUtils.encodeURIComponent("€"));
    }

    @Test
    public void encodeURIComponentEncodesNewlineAsPercent0A() {
        assertEquals("%0A", WebUtils.encodeURIComponent("\n"));
    }

    @Test
    public void encodeURIComponentReturnsNullForNullInput() {
        // FIXED (WU1): the catch clause now catches Exception, so URLEncoder.encode(null,..)'s
        // NullPointerException is swallowed and the (null) input is returned, consistent with
        // urlDecode(null) which also catches Exception and returns null.
        assertNull(WebUtils.encodeURIComponent(null));
    }

    // ------------------------------------------------------------------
    // encodeURI(String) - divergence from a faithful JS encodeURI()
    // ------------------------------------------------------------------

    @Test
    public void encodeURIDivergesFromJsByStillEncodingAtCommaPlusDollarHash() {
        // A faithful JS encodeURI() leaves ; , / ? : @ & = + $ - _ . ! ~ * ' ( ) # untouched.
        // This implementation only re-decodes %3A %2F %3B, so @ , + $ # remain percent-encoded (and ? ' too,
        // see encodeURIEncodesAQuestionMarkAndAnApostropheInAName).
        assertEquals("%40", WebUtils.encodeURI("@"));
        assertEquals("%2C", WebUtils.encodeURI(","));
        assertEquals("%2B", WebUtils.encodeURI("+"));
        assertEquals("%24", WebUtils.encodeURI("$"));
        assertEquals("%23", WebUtils.encodeURI("#"));
    }

    @Test
    public void encodeURIRestoresOnlyTheThreeStructuralCharactersInAMixedReservedString() {
        // Of ";,/?:@&=+$#" only ; / : are restored; the rest, the question mark among them, stay percent-encoded.
        assertEquals(";%2C/%3F:%40%26%3D%2B%24%23", WebUtils.encodeURI(";,/?:@&=+$#"));
    }

    @Test
    public void encodeURIEncodesAQuestionMarkAndAnApostropheInAName() {
        // REPO4: a repository path's link ends at a raw "?" (the query string) and at a raw "'" (the reader of
        // Niord's own links, RepoPaths.fileLinkPaths), so both are encoded where a name holds them. A faithful JS
        // encodeURI() would leave both raw.
        assertEquals("messages/a/what%3F.pdf", WebUtils.encodeURI("messages/a/what?.pdf"));
        assertEquals("messages/a/it%27s.pdf", WebUtils.encodeURI("messages/a/it's.pdf"));
        assertEquals("%3F%27", WebUtils.encodeURI("?'"));
        // encodeURIComponent keeps the apostrophe, as JavaScript's does
        assertEquals("it's", WebUtils.encodeURIComponent("it's"));
        // The other characters JavaScript leaves raw are kept, as before
        assertEquals("a/b%20(1)!~*-_.:;", WebUtils.encodeURI("a/b (1)!~*-_.:;"));
    }

    @Test
    public void encodeURIPreservesMultiByteUtf8AsEncodeURIComponentDoes() {
        // encodeURI delegates to encodeURIComponent and only re-decodes : / ; afterwards, so
        // multi-byte characters are encoded identically by both methods.
        assertEquals("%E2%82%AC", WebUtils.encodeURI("€"));
    }

    // ------------------------------------------------------------------
    // urlDecode(String) - null + try/catch fallback branch
    // ------------------------------------------------------------------

    @Test
    public void urlDecodeReturnsNullForNullInput() {
        // URLDecoder.decode(null, ..) throws NPE, which the broad "catch (Exception)" swallows; the
        // method then returns the original (null) value.
        assertNull(WebUtils.urlDecode(null));
    }

    @Test
    public void urlDecodeReturnsOriginalForNonHexEscape() {
        // "%GG" is not a valid hex escape -> IllegalArgumentException -> caught -> verbatim input.
        assertEquals("%GG", WebUtils.urlDecode("%GG"));
    }

    @Test
    public void urlDecodeReturnsOriginalForTruncatedEscape() {
        // "%2" is missing the second hex digit -> exception -> verbatim input.
        assertEquals("%2", WebUtils.urlDecode("%2"));
    }

    @Test
    public void urlDecodeReturnsOriginalForTrailingPercent() {
        // A '%' with nothing after it -> exception -> verbatim input.
        assertEquals("100%", WebUtils.urlDecode("100%"));
    }

    @Test
    public void urlDecodeDecodesPercentEncodedSlash() {
        // Valid escape on the happy path: %2F -> '/'.
        assertEquals("/", WebUtils.urlDecode("%2F"));
    }

    @Test
    public void urlDecodeDecodesMultiByteUtf8Octets() {
        // The UTF-8 octet triple for the euro sign decodes back to the single character.
        assertEquals("€", WebUtils.urlDecode("%E2%82%AC"));
    }

    // ------------------------------------------------------------------
    // round-trip: encode then decode recovers the original
    // ------------------------------------------------------------------

    @Test
    public void urlDecodeRecoversReservedCharactersFromEncodeURIComponent() {
        // Reserved punctuation that encodeURIComponent escapes is fully recovered by urlDecode.
        String original = ";?&=:/@,+$#";
        assertEquals(original, WebUtils.urlDecode(WebUtils.encodeURIComponent(original)));
    }

    @Test
    public void urlDecodeRecoversMultiByteCharacterFromEncodeURIComponent() {
        assertEquals("€æ", WebUtils.urlDecode(WebUtils.encodeURIComponent("€æ")));
    }

    @Test
    public void urlDecodeRecoversReservedCharactersFromEncodeURI() {
        // Even with encodeURI's partial restoration, the structural and still-encoded characters all
        // decode back to the original input.
        String original = "a:b/c;d?e&f=g@h'i";
        assertEquals(original, WebUtils.urlDecode(WebUtils.encodeURI(original)));
    }
}
