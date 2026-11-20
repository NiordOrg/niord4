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

package org.niord.core.script;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.niord.core.NiordApp;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.slf4j.Logger;

import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main interface for accessing and processing script resource, i.e. Freemarker templates and JavaScript resources
 */
@Service
public class ScriptResourceService extends BaseService {

    private final UserService userService;

    private final NiordApp app;

    ScriptResourceService(UserService userService, NiordApp app) {
        this.userService = userService;
        this.app = app;
    }

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScriptResourceService.class);

    /**
     * Saves the script resource
     *
     * @param resource the script resource to save
     * @return the saved resource
     */
    @Transactional
    public ScriptResource saveScriptResource(ScriptResource resource) {
        return saveScriptResource(resource, null);
    }

    /**
     * Saves the script resource, recording the given user in its history
     *
     * @param resource the script resource to save
     * @param importer the user the history records, or null for the current user
     * @return the saved resource
     */
    @Transactional
    public ScriptResource saveScriptResource(ScriptResource resource, User importer) {
        return saveScriptResource(resource, importer, null);
    }

    /**
     * Saves the script resource, recording the given user and what wrote it in its history
     *
     * @param resource the script resource to save
     * @param importer the user the history records, or null for the current user
     * @param source   what wrote it ({@link ScriptResourceHistory#SOURCE_IMPORT},
     *                 {@link ScriptResourceHistory#SOURCE_EDITOR}), or null
     * @return the saved resource
     */
    @Transactional
    public ScriptResource saveScriptResource(ScriptResource resource, User importer, String source) {

        // Update the type from the path extension
        resource.updateType();

        // Save the resource
        resource = saveEntity(resource);

        // Save a ScriptResourceHistory entity for the resource
        saveScriptResourceHistory(resource, importer, source);

        return resource;
    }

    /**
     * Returns the script resource with the given ID, or null if not found
     * @param id the resource id
     * @return the script resource with the given ID, or null if not found
     */
    public ScriptResource findById(Integer id) {
        return getByPrimaryKey(ScriptResource.class, id);
    }

    /**
     * Returns the script resource with the given path, or null if not found
     * <p>
     * The path is compared ignoring case, while the schema holds it unique with case: when several resources have
     * the path in different case (restored data), the one whose path is exactly the given one is returned, as the
     * NAVTEX transmitter and SafetyNET area look-ups do; when none is, none is returned, as when there is none, and a
     * WARN names the path.
     *
     * @param path the script resource path
     * @return the script resource with the given path, or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public ScriptResource findByPath(String path) {
        List<ScriptResource> matches = em.createNamedQuery("ScriptResource.findByPath", ScriptResource.class)
                .setParameter("path", path)
                .getResultList();
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.getFirst();
        }
        ScriptResource exact = matches.stream()
                .filter(r -> Objects.equals(r.getPath(), path))
                .findFirst()
                .orElse(null);
        if (exact == null) {
            log.warn("Several script resources have the path '{}' in different case and none has it exactly; the "
                    + "look-up by path answers none of them", path);
        }
        return exact;
    }

    /**
     * Returns the script resources with the types
     * @param types the script resource types
     * @return the script resources with the given types
     */
    public List<ScriptResource> findByTypes(ScriptResource.Type... types) {

        Set<ScriptResource.Type> typeSet = Arrays.stream(types).collect(Collectors.toSet());

        return em.createNamedQuery("ScriptResource.findByTypes", ScriptResource.class)
                .setParameter("types", typeSet)
                .getResultList();
    }

    /**
     * Returns all script resources
     * @return all script resources
     */
    public List<ScriptResource> findAll() {
        return em.createNamedQuery("ScriptResource.findAll", ScriptResource.class)
                .getResultList();
    }

    /**
     * Returns all script resource paths
     * @return all script resource paths
     */
    public Set<String> findAllScriptResourcePaths() {
        return new HashSet<>(em.createNamedQuery("ScriptResource.findAllPaths", String.class)
                .getResultList());
    }

    /**
     * Creates a new script resource based on the resource parameter
     * @param resource the resource to create
     * @return the created script resource
     */
    public ScriptResource createScriptResource(ScriptResource resource) {
        return createScriptResource(resource, null);
    }

    /**
     * Creates a new script resource based on the resource parameter, recording the given user in its history
     * @param resource the resource to create
     * @param importer the user the history records, or null for the current user
     * @return the created script resource
     */
    public ScriptResource createScriptResource(ScriptResource resource, User importer) {
        return createScriptResource(resource, importer, null);
    }

    /**
     * Creates a new script resource based on the resource parameter, recording the given user and what wrote it in
     * its history
     * @param resource the resource to create
     * @param importer the user the history records, or null for the current user
     * @param source   what wrote it ({@link ScriptResourceHistory#SOURCE_IMPORT},
     *                 {@link ScriptResourceHistory#SOURCE_EDITOR}), or null
     * @return the created script resource
     */
    public ScriptResource createScriptResource(ScriptResource resource, User importer, String source) {
        if (resource.isPersisted()) {
            throw new IllegalArgumentException("Cannot create script resource with existing ID "
                    + resource.getId());
        }
        refuseInvalidPath(resource.getPath());

        ScriptResource original = findByPath(resource.getPath());
        if (original != null) {
            throw new IllegalArgumentException("Cannot create script resource with duplicate path " + resource.getPath());
        }

        return saveScriptResource(resource, importer, source);
    }

    /**
     * Updates the script resource data from the resource parameter
     * @param resource the script resource to update
     * @return the updated script resource
     */
    public ScriptResource updateScriptResource(ScriptResource resource) {
        return updateScriptResource(resource, null);
    }

    /**
     * Updates the script resource data from the resource parameter, recording the given user in its history
     * @param resource the script resource to update
     * @param importer the user the history records, or null for the current user
     * @return the updated script resource
     */
    public ScriptResource updateScriptResource(ScriptResource resource, User importer) {
        return updateScriptResource(resource, importer, null);
    }

    /**
     * Updates the script resource data from the resource parameter, recording the given user and what wrote it in
     * its history
     * @param resource the script resource to update
     * @param importer the user the history records, or null for the current user
     * @param source   what wrote it ({@link ScriptResourceHistory#SOURCE_IMPORT},
     *                 {@link ScriptResourceHistory#SOURCE_EDITOR}), or null
     * @return the updated script resource
     */
    public ScriptResource updateScriptResource(ScriptResource resource, User importer, String source) {
        ScriptResource original = findById(resource.getId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing script resource " + resource.getPath());
        }
        // Checked before anything is copied into the managed row, so the lookup cannot flush a half-made change
        refuseInvalidPath(resource.getPath());
        ScriptResource other = findByPath(resource.getPath());
        if (other != null && !Objects.equals(other.getId(), original.getId())) {
            throw new IllegalArgumentException("Another script resource has the path " + resource.getPath());
        }

        // Copy the resource data
        original.setType(resource.getType());
        original.setPath(resource.getPath());
        original.setContent(resource.getContent());

        return saveScriptResource(original, importer, source);
    }

    /**
     * Refuses a path a script resource cannot be saved with: a blank one, or one whose type cannot be read from its
     * extension ({@link ScriptResource#path2type}), as the save sets the type from the path and the type is required
     *
     * @param path the path to check
     * @throws IllegalArgumentException naming the path when it is refused
     */
    private static void refuseInvalidPath(String path) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("A script resource needs a path");
        }
        if (ScriptResource.path2type(path) == null) {
            throw new IllegalArgumentException("The script resource path " + path + " must end in .ftl or .js");
        }
    }

    /**
     * Deletes the script resource with the given path
     * @param id the ID of the script resource to delete
     * @noinspection all
     */
    @Transactional
    public boolean deleteScriptResource(Integer id) {

        ScriptResource resource = findById(id);
        if (resource != null) {
            // Delete all resource history entries
            getScriptResourceHistory(id).forEach(this::remove);
            // Delete the actual resource
            remove(resource);
            return true;
        }
        return false;
    }

    /***************************************/
    /** Script Resource History           **/
    /***************************************/

    /**
     * Saves a history entity containing a snapshot of the script resource
     *
     * @param resource the resource to save a snapshot for
     */
    @Transactional
    public void saveScriptResourceHistory(ScriptResource resource) {
        saveScriptResourceHistory(resource, null);
    }

    /**
     * Saves a history entity containing a snapshot of the script resource, recording the given user.
     * An import runs on a batch thread with no security context, so it names the importing user here.
     *
     * @param resource the resource to save a snapshot for
     * @param importer the user the history records, or null for the current user
     */
    @Transactional
    public void saveScriptResourceHistory(ScriptResource resource, User importer) {
        saveScriptResourceHistory(resource, importer, null);
    }

    /**
     * Saves a history entity containing a snapshot of the script resource, recording the given user and what wrote
     * the resource.
     *
     * @param resource the resource to save a snapshot for
     * @param importer the user the history records, or null for the current user
     * @param source   what wrote it ({@link ScriptResourceHistory#SOURCE_IMPORT},
     *                 {@link ScriptResourceHistory#SOURCE_EDITOR}), or null
     */
    @Transactional
    public void saveScriptResourceHistory(ScriptResource resource, User importer, String source) {

        try {
            ScriptResourceHistory hist = new ScriptResourceHistory();
            hist.setResource(resource);
            hist.setUser(importer != null
                    ? em.getReference(User.class, importer.getId())
                    : userService.currentUser());
            hist.setCreated(new Date());
            hist.setVersion(resource.getVersion() + 1);
            hist.setSource(source);

            // Create a snapshot of the resource
            ObjectMapper jsonMapper = JsonMapper.builderWithJackson2Defaults()
                    .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false) // Use ISO-8601 format
                    .build();
            ScriptResourceVo snapshot = resource.toVo();
            hist.compressSnapshot(jsonMapper.writeValueAsString(snapshot));

            saveEntity(hist);

        } catch (Exception e) {
            log.error("Error saving a history entry for resource " + resource.getId(), e);
            // NB: Don't propagate errors
        }
    }

    /**
     * Returns the script resource history for the given template ID
     *
     * @param resourceId the template ID
     * @return the template history
     */
    public List<ScriptResourceHistory> getScriptResourceHistory(Integer resourceId) {
        return em.createNamedQuery("ScriptResourceHistory.findByResourceId", ScriptResourceHistory.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }
}
