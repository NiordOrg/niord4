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

package org.niord.core.source;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;

import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.NoResultException;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business interface for accessing sources
 */
@Service
@SuppressWarnings("unused")
public class SourceService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SourceService.class);

    /**
     * Returns the source with the given ID
     * @param id the ID
     * @return the source with the given ID or null if not found
     */
    public Source findById(Integer id) {
        return getByPrimaryKey(Source.class, id);
    }

    /**
     * Searches for sources matching the given term
     *
     * @param term the search term
     * @param lang the search language
     * @param inactive whether to include inactive sources as well as active
     * @param limit the maximum number of results
     * @return the search result
     */
    public List<Source> searchSources(String lang, String term, boolean inactive, int limit) {

        term = StringUtils.defaultIfBlank(term, "");

        Set<Boolean> activeFlag = new HashSet<>();
        activeFlag.add(Boolean.TRUE);
        if (inactive) {
            activeFlag.add(Boolean.FALSE);
        }

        return em
                .createNamedQuery("Source.searchSources", Source.class)
                .setParameter("active", activeFlag)
                .setParameter("lang", lang)
                .setParameter("term", "%" + term.toLowerCase() + "%")
                .getResultList()
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Returns the source with the given IDs
     *
     * @param ids the ID's of the sources to fetch
     * @return the source with the given IDs
     */
    public List<Source> findByIds(Set<Integer> ids) {
        if (ids != null && !ids.isEmpty()) {
            return em
                    .createNamedQuery("Source.findByIds", Source.class)
                    .setParameter("ids", ids)
                    .getResultList();
        }
        return Collections.emptyList();
    }

    /**
     * Returns the source with the given name or null if not found
     * <p>
     * A source's name is not unique in the schema: when several sources have the name in the language, compared
     * ignoring case (restored data), none is returned, as when there is none, and a WARN names the name.
     *
     * @param lang the search language
     * @param name the search name
     * @return the source with the given name or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public Source findByName(String lang, String name) {

        if (StringUtils.isNotBlank(name)) {
            Source source;
            try {
                source = em
                        .createNamedQuery("Source.findByName", Source.class)
                        .setParameter("lang", lang)
                        .setParameter("name", name.toLowerCase())
                        .getSingleResult();
            } catch (NoResultException e) {
                return null;
            } catch (NonUniqueResultException e) {
                log.warn("Several sources have the {} name '{}'; the look-up by name answers none of them", lang, name);
                return null;
            }
            // Load the descs while this method's transaction is open: the source import
            // (BatchSourceImportProcessor) updates the found source on the batch thread,
            // after the transaction has ended, and the update reads the descs.
            source.getDescs().size();
            return source;
        }
        return null;
    }

    /**
     * Ensures that the template source exists.
     *
     * @param templateSource the template source
     * @param create whether to create a missing source or just find it
     * @return the source
     */
    public Source findOrCreateSource(Source templateSource, Boolean create) {

        if (templateSource == null) {
            return null;
        }

        // Search for the source by name
        for (SourceDesc desc : templateSource.getDescs()) {
            Source source = findByName(desc.getLang(), desc.getName());
            if (source != null) {
                return source;
            }
        }

        // If not found, and requested, create the source
        if (create) {
            templateSource.setId(null);
            return createSource(templateSource);
        }

        return null;
    }

    /**
     * Returns the list of sources
     * @return the list of sources
     */
    public List<Source> getSources() {
        return getAll(Source.class);
    }

    /**
     * Updates the source data from the source template
     * @param source the source to update
     * @return the updated source
     */
    @Transactional
    public Source updateSource(Source source) {
        Source original = findById(source.getId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing source "
                    + source.getId());
        }

        // Copy the source data.
        // NB: do NOT clear() the descs first - copyDescsAndRemoveBlanks() updates them in place by
        // language (reusing existing descs and removing blanks), exactly like AreaService/CategoryService.
        // A preceding clear() either orphan-removes every desc (when the managed entity is its own update
        // template) or triggers a duplicate (lang, entity_id) key on flush (detached REST template). See
        // docs/potential-errors.md (SRC1).
        original.setActive(source.isActive());
        original.copyDescsAndRemoveBlanks(source.getDescs());

        return saveEntity(original);
    }

    /**
     * Creates a new source based on the source template
     * @param source the source to create
     * @return the created source
     */
    @Transactional
    public Source createSource(Source source) {
        if (!source.isNew()) {
            throw new IllegalArgumentException("Cannot create source with existing ID "
                    + source.getId());
        }

        return saveEntity(source);
    }

    /**
     * Deletes the source with the given ID
     * @param id the id of the source to delete
     */
    @Transactional
    public boolean deleteSource(Integer id) {

        Source source = findById(id);
        if (source != null) {
            remove(source);
            return true;
        }
        return false;
    }

}
