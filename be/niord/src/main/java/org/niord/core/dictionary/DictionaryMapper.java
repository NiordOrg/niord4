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
package org.niord.core.dictionary;

import org.niord.core.dictionary.vo.DictionaryEntryDescVo;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Dictionary} aggregate (including the owned {@link DictionaryEntry} and
 * {@link DictionaryEntryDesc} children) and its value objects.
 *
 * <p>Replaces the {@code toVo()}/conversion-constructor/{@code updateDictionaryEntry()} logic that was
 * previously embedded in the {@link Dictionary}, {@link DictionaryEntry} and {@link DictionaryEntryDesc}
 * entities. Conversion lives here as a stateless, dependency-injected bean so it can be unit-tested
 * without a persistence context. The owned children are mapped inline as private helper methods.
 */
@Component
public class DictionaryMapper {

    /** Converts the entity to a value object, honouring the data filter. */
    public DictionaryVo toVo(Dictionary dictionary, DataFilter filter) {
        if (dictionary == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(Dictionary.class);

        DictionaryVo dict = new DictionaryVo();
        dict.setName(dictionary.getName());
        dict.setEntries(dictionary.getEntries().values().stream()
                .map(de -> toVo(de, compFilter))
                .collect(Collectors.toMap(DictionaryEntryVo::getKey, Function.identity()))
        );

        return dict;
    }

    /** Converts the dictionary entry entity to a value object, honouring the data filter. */
    private DictionaryEntryVo toVo(DictionaryEntry entry, DataFilter filter) {

        DataFilter compFilter = filter.forComponent(DictionaryEntry.class);

        DictionaryEntryVo entryVo = new DictionaryEntryVo();
        entryVo.setId(entry.getId());
        entryVo.setKey(entry.getKey());
        entryVo.setAtonFilter(entry.getAtonFilter());
        if (!entry.getDescs().isEmpty()) {
            entryVo.setDescs(entry.getDescs(filter).stream()
                    .map(this::toVo)
                    .collect(Collectors.toList()));
        }
        return entryVo;
    }

    /** Converts the dictionary entry description entity to a value object. */
    private DictionaryEntryDescVo toVo(DictionaryEntryDesc desc) {
        DictionaryEntryDescVo descVo = new DictionaryEntryDescVo();
        descVo.setLang(desc.getLang());
        descVo.setValue(desc.getValue());
        descVo.setLongValue(desc.getLongValue());
        return descVo;
    }

    /** Creates a new {@link Dictionary} entity from the given value object. */
    public Dictionary toEntity(DictionaryVo vo) {
        if (vo == null) {
            return null;
        }
        Dictionary dictionary = new Dictionary();
        updateEntity(dictionary, vo);
        return dictionary;
    }

    /** Updates an existing {@link Dictionary} entity from the given value object. */
    public void updateEntity(Dictionary dictionary, DictionaryVo vo) {
        dictionary.setName(vo.getName());
        vo.getEntries().values().forEach(entry ->
                dictionary.createEntry(entry.getKey()).updateDictionaryEntry(toEntity(entry)));
    }

    /** Creates a new {@link DictionaryEntry} entity from the given value object. */
    private DictionaryEntry toEntity(DictionaryEntryVo entry) {
        DictionaryEntry de = new DictionaryEntry();
        de.setId(entry.getId());
        de.setKey(entry.getKey());
        de.setAtonFilter(entry.getAtonFilter());
        if (entry.getDescs() != null) {
            entry.getDescs()
                    .forEach(desc -> de.createDesc(desc.getLang()).copyDesc(toEntity(desc)));
        }
        return de;
    }

    /** Creates a new {@link DictionaryEntryDesc} entity from the given value object. */
    private DictionaryEntryDesc toEntity(DictionaryEntryDescVo desc) {
        DictionaryEntryDesc d = new DictionaryEntryDesc();
        d.setLang(desc.getLang());
        d.setValue(desc.getValue());
        d.setLongValue(desc.getLongValue());
        return d;
    }
}
