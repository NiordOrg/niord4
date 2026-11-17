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

import java.util.List;

/**
 * What uses a dictionary key, as the Dictionaries page's delete confirmation shows it
 * (docs/code-data-config-plan.md §4.6)
 */
@SuppressWarnings("unused")
public class DictionaryKeyUsersVo implements IJsonSerializable {

    /** The paths of the script resources that name the key, or its runtime prefix, sorted */
    List<String> templates;

    /** The runtime prefix the scan also used, when the key has one (such as {@code msg.status.}); absent otherwise */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String prefix;

    /** The names of the {@code RequiredTexts.Reader}s that read the key from this dictionary, in enum order */
    List<String> readers;

    /** The names of the list parameter types whose values hold the key, sorted; a delete is refused while any does */
    List<String> paramTypes;

    public DictionaryKeyUsersVo() {
    }

    public DictionaryKeyUsersVo(List<String> templates, String prefix, List<String> readers, List<String> paramTypes) {
        this.templates = templates;
        this.prefix = prefix;
        this.readers = readers;
        this.paramTypes = paramTypes;
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public List<String> getTemplates() {
        return templates;
    }

    public void setTemplates(List<String> templates) {
        this.templates = templates;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public List<String> getReaders() {
        return readers;
    }

    public void setReaders(List<String> readers) {
        this.readers = readers;
    }

    public List<String> getParamTypes() {
        return paramTypes;
    }

    public void setParamTypes(List<String> paramTypes) {
        this.paramTypes = paramTypes;
    }
}
