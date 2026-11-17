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
package org.niord.core.dictionary.vo;

import org.niord.model.IJsonSerializable;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Models a named dictionary
 */
public class DictionaryVo implements IJsonSerializable {

    String name;
    Map<String, DictionaryEntryVo> entries = new HashMap<>();

    /**
     * Creates a Properties object from the dictionary, containing the values for the given language
     * where defined, and otherwise, any value.
     * @param lang the language
     * @return the Properties object for the dictionary
     */
    public Properties toProperties(String lang) {
        return toProperties(lang, null);
    }

    public Properties toProperties(String lang, String defaultLanguage) {
        Properties props = new Properties();
        getEntries().values().forEach(entry -> {
            String value = localizedValue(entry, lang, defaultLanguage);
            if (value != null) props.setProperty(entry.getKey(), value);
        });
        return props;
    }

    /**
     * Shortcut for getting a dictionary value
     *
     * @param lang the language of the dictionary
     * @param key the key
     */
    public String value(String lang, String key) {
        DictionaryEntryVo entry = entries.get(key);
        if (entry != null) {
            DictionaryEntryDescVo desc = entry.getDesc(lang);
            return desc != null ? desc.getValue() : null;
        }
        return null;
    }

    /**
     * Looks up one key: the language's own value, an empty one included; when the language has no description or
     * a null value, the default language's, then the first non-blank value.
     */
    public String value(String lang, String key, String defaultLanguage) {
        DictionaryEntryVo entry = entries.get(key);
        return entry == null ? null : localizedValue(entry, lang, defaultLanguage);
    }

    /**
     * Returns the entry's value in the given language. A value that is empty on purpose (the English
     * {@code navwarn.date.time_interval_prefix}) is that language's text and is kept; only a language without a
     * description, or with a null value, falls back to the default language and then to the first non-blank value.
     */
    private static String localizedValue(DictionaryEntryVo entry, String lang, String defaultLanguage) {
        DictionaryEntryDescVo desc = entry.getDesc(lang);
        if (desc == null || desc.getValue() == null) {
            desc = entry.getDesc(defaultLanguage);
            if (desc == null || desc.getValue() == null) {
                desc = entry.getDescs().stream()
                        .filter(d -> d.getValue() != null && !d.getValue().isBlank())
                        .findFirst().orElse(null);
            }
        }
        return desc == null ? null : desc.getValue();
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, DictionaryEntryVo> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, DictionaryEntryVo> entries) {
        this.entries = entries;
    }
}
