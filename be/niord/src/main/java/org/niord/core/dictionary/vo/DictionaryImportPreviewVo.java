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
package org.niord.core.dictionary.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.niord.model.IJsonSerializable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a dictionary import file would do, which the import dialog shows before anything is written
 * (docs/code-data-config-plan.md §4.6): the keys it adds, the keys it would overwrite with the row's values and the
 * file's, how many keys it leaves unchanged, and the entries it refuses with their reasons. A key is one
 * (dictionary, key); the lists are sorted by dictionary and key.
 */
@SuppressWarnings("unused")
public class DictionaryImportPreviewVo implements IJsonSerializable {

    List<Add> add = new ArrayList<>();
    List<Overwrite> overwrite = new ArrayList<>();
    int unchanged;
    List<Refused> refused = new ArrayList<>();

    /** An entry's values: the AtoN filter, and the value and long value of each language */
    public static class Values implements IJsonSerializable {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String atonFilter;
        Map<String, String> values = new TreeMap<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> longValues = new TreeMap<>();

        public String getAtonFilter() {
            return atonFilter;
        }

        public void setAtonFilter(String atonFilter) {
            this.atonFilter = atonFilter;
        }

        public Map<String, String> getValues() {
            return values;
        }

        public void setValues(Map<String, String> values) {
            this.values = values;
        }

        public Map<String, String> getLongValues() {
            return longValues;
        }

        public void setLongValues(Map<String, String> longValues) {
            this.longValues = longValues;
        }
    }

    /**
     * A key the database lacks, with the file's values; {@code deleted} when an operator deleted the key (the
     * defaults ledger records it), so the import adds it only when the sysadmin ticks it
     */
    public static class Add extends Values {
        String dictionary;
        String key;
        boolean deleted;

        public String getDictionary() {
            return dictionary;
        }

        public void setDictionary(String dictionary) {
            this.dictionary = dictionary;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }
    }

    /**
     * A key the database has with other values: the row's ({@code current}) and the file's ({@code incoming}), and
     * {@code basedOn}, the fingerprint of the row's values ({@code sha256:<64 hex>}), which a tick sends back so that
     * the job keeps a key changed since the preview
     */
    public static class Overwrite implements IJsonSerializable {
        String dictionary;
        String key;
        Values current;
        Values incoming;
        String basedOn;

        public String getBasedOn() {
            return basedOn;
        }

        public void setBasedOn(String basedOn) {
            this.basedOn = basedOn;
        }

        public String getDictionary() {
            return dictionary;
        }

        public void setDictionary(String dictionary) {
            this.dictionary = dictionary;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Values getCurrent() {
            return current;
        }

        public void setCurrent(Values current) {
            this.current = current;
        }

        public Values getIncoming() {
            return incoming;
        }

        public void setIncoming(Values incoming) {
            this.incoming = incoming;
        }
    }

    /** An entry the import refuses, and why */
    public static class Refused implements IJsonSerializable {
        String dictionary;
        String key;
        String reason;

        public Refused() {
        }

        public Refused(String dictionary, String key, String reason) {
            this.dictionary = dictionary;
            this.key = key;
            this.reason = reason;
        }

        public String getDictionary() {
            return dictionary;
        }

        public void setDictionary(String dictionary) {
            this.dictionary = dictionary;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public List<Add> getAdd() {
        return add;
    }

    public void setAdd(List<Add> add) {
        this.add = add;
    }

    public List<Overwrite> getOverwrite() {
        return overwrite;
    }

    public void setOverwrite(List<Overwrite> overwrite) {
        this.overwrite = overwrite;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public void setUnchanged(int unchanged) {
        this.unchanged = unchanged;
    }

    public List<Refused> getRefused() {
        return refused;
    }

    public void setRefused(List<Refused> refused) {
        this.refused = refused;
    }
}
