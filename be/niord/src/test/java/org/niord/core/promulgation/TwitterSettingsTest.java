/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterSettingsVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for the {@link TwitterSettings} entity.
 * <p>
 * These tests verify pure POJO behaviour: VO-to-entity copying, the security-critical
 * rule that {@code toVo()} never leaks the two secret credentials back to the client,
 * the field-level semantics of {@code updateSettings()}, and the {@code credentialsValid()}
 * validation logic. No CDI, persistence, mocking or Quarkus runtime is involved.
 */
public class TwitterSettingsTest {

    /** Builds a PromulgationTypeVo carrying the given typeId. */
    private static PromulgationTypeVo typeVo(String typeId) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        return vo;
    }

    /** Builds a TwitterSettings entity with all seven fields populated. */
    private static TwitterSettings fullySetEntity(String typeId,
                                                  String format,
                                                  Boolean includeThumbnail,
                                                  String apiKey,
                                                  String apiSecret,
                                                  String accessToken,
                                                  String accessTokenSecret) {
        TwitterSettings settings = new TwitterSettings();
        settings.setPromulgationType(new PromulgationType(typeVo(typeId)));
        settings.setFormat(format);
        settings.setIncludeThumbnail(includeThumbnail);
        settings.setApiKey(apiKey);
        settings.setApiSecret(apiSecret);
        settings.setAccessToken(accessToken);
        settings.setAccessTokenSecret(accessTokenSecret);
        return settings;
    }

    /** Builds an entity with all four credentials valid (and format/thumbnail set). */
    private static TwitterSettings validCredentialsEntity() {
        return fullySetEntity("tw", "F", Boolean.TRUE, "K", "KS", "AT", "ATS");
    }

    // ------------------------------------------------------------------------
    // (1) Constructor-from-VO copies all seven fields
    // ------------------------------------------------------------------------

    @Test
    public void constructorFromVoCopiesAllSevenFields() {
        TwitterSettingsVo vo = new TwitterSettingsVo();
        vo.setPromulgationType(typeVo("tw"));
        vo.setFormat("F");
        vo.setIncludeThumbnail(Boolean.TRUE);
        vo.setApiKey("K");
        vo.setApiSecret("KS");
        vo.setAccessToken("AT");
        vo.setAccessTokenSecret("ATS");

        TwitterSettings settings = new TwitterSettings(vo);

        assertEquals("tw", settings.getPromulgationType().getTypeId(),
                "promulgationType.typeId should be copied from the VO");
        assertEquals("F", settings.getFormat(), "format should be copied from the VO");
        assertEquals(Boolean.TRUE, settings.getIncludeThumbnail(),
                "includeThumbnail should be copied from the VO");
        assertEquals("K", settings.getApiKey(), "apiKey should be copied from the VO");
        assertEquals("KS", settings.getApiSecret(), "apiSecret should be copied from the VO");
        assertEquals("AT", settings.getAccessToken(), "accessToken should be copied from the VO");
        assertEquals("ATS", settings.getAccessTokenSecret(),
                "accessTokenSecret should be copied from the VO");
    }

    // ------------------------------------------------------------------------
    // (2) SECURITY: toVo() must never leak the two secrets
    // ------------------------------------------------------------------------

    @Test
    public void toVoCopiesNonSecretFieldsButNeverLeaksSecrets() {
        TwitterSettings settings =
                fullySetEntity("tw", "F", Boolean.TRUE, "K", "SECRET1", "AT", "SECRET2");

        TwitterSettingsVo vo = settings.toVo();

        // Non-secret fields are exposed to the client.
        assertEquals("K", vo.getApiKey(), "apiKey should be exposed in the VO");
        assertEquals("AT", vo.getAccessToken(), "accessToken should be exposed in the VO");
        assertEquals("F", vo.getFormat(), "format should be exposed in the VO");
        assertEquals(Boolean.TRUE, vo.getIncludeThumbnail(),
                "includeThumbnail should be exposed in the VO");
        assertEquals("tw", vo.getPromulgationType().getTypeId(),
                "promulgationType.typeId should be exposed in the VO");

        // SECURITY: the two secret credentials are deliberately NOT copied to the VO
        // ("We never send passwords back to the client").
        assertNull(vo.getApiSecret(), "apiSecret must NOT be leaked to the client");
        assertNull(vo.getAccessTokenSecret(),
                "accessTokenSecret must NOT be leaked to the client");
    }

    // ------------------------------------------------------------------------
    // (3) updateSettings() updates the six fields but never the promulgationType
    // ------------------------------------------------------------------------

    @Test
    public void updateSettingsCopiesSixFieldsButLeavesPromulgationTypeUnchanged() {
        TwitterSettings original = fullySetEntity(
                "tw", "oldFormat", Boolean.FALSE,
                "oldKey", "oldSecret", "oldToken", "oldTokenSecret");

        TwitterSettings source = fullySetEntity(
                "other", "newFormat", Boolean.TRUE,
                "newKey", "newSecret", "newToken", "newTokenSecret");

        original.updateSettings(source);

        assertEquals("newFormat", original.getFormat(),
                "format should be updated to the source value");
        assertEquals(Boolean.TRUE, original.getIncludeThumbnail(),
                "includeThumbnail should be updated to the source value");
        assertEquals("newKey", original.getApiKey(),
                "apiKey should be updated to the source value");
        assertEquals("newSecret", original.getApiSecret(),
                "apiSecret should be updated to the source value");
        assertEquals("newToken", original.getAccessToken(),
                "accessToken should be updated to the source value");
        assertEquals("newTokenSecret", original.getAccessTokenSecret(),
                "accessTokenSecret should be updated to the source value");

        // updateSettings() must NOT touch the promulgationType.
        assertEquals("tw", original.getPromulgationType().getTypeId(),
                "promulgationType must remain the original type after updateSettings");
    }

    // ------------------------------------------------------------------------
    // (4) credentialsValid(): all four credentials must be non-blank
    // ------------------------------------------------------------------------

    @Test
    public void credentialsValidIsTrueWhenAllFourCredentialsArePresent() {
        assertTrue(validCredentialsEntity().credentialsValid(),
                "credentialsValid() should be true when all four credentials are non-blank");
    }

    @Test
    public void credentialsValidIsFalseWhenApiKeyIsNull() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setApiKey(null);
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when apiKey is null");
    }

    @Test
    public void credentialsValidIsFalseWhenApiSecretIsNull() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setApiSecret(null);
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when apiSecret is null");
    }

    @Test
    public void credentialsValidIsFalseWhenAccessTokenIsNull() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setAccessToken(null);
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when accessToken is null");
    }

    @Test
    public void credentialsValidIsFalseWhenAccessTokenSecretIsNull() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setAccessTokenSecret(null);
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when accessTokenSecret is null");
    }

    @Test
    public void credentialsValidIsFalseWhenApiKeyIsBlank() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setApiKey("  ");
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when apiKey is blank whitespace");
    }

    @Test
    public void credentialsValidIsFalseWhenApiSecretIsBlank() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setApiSecret("  ");
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when apiSecret is blank whitespace");
    }

    @Test
    public void credentialsValidIsFalseWhenAccessTokenIsBlank() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setAccessToken("  ");
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when accessToken is blank whitespace");
    }

    @Test
    public void credentialsValidIsFalseWhenAccessTokenSecretIsBlank() {
        TwitterSettings settings = validCredentialsEntity();
        settings.setAccessTokenSecret("  ");
        assertFalse(settings.credentialsValid(),
                "credentialsValid() should be false when accessTokenSecret is blank whitespace");
    }
}
