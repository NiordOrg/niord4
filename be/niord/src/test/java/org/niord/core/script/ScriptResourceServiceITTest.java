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
package org.niord.core.script;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.script.ScriptResource.Type;
import org.niord.core.user.User;
import org.niord.test.ITTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ScriptResourceService} — the persistence gateway for Freemarker templates
 * and JavaScript resources stored in the {@code ScriptResource} table.
 *
 * <p>Each test runs inside a rolled-back {@link TestTransaction}; assertions verify the actual database
 * state via {@code em.flush()/clear()/find()} or follow-up service queries. The seed contains no
 * {@code ScriptResource} rows, so {@code findAll}/{@code findByTypes} return exactly what the test creates.
 *
 * <p>This is a framework-coupled integration test (Quarkus DI + Hibernate); see
 * {@code docs/framework-coupled-tests.md}.
 */
class ScriptResourceServiceITTest extends ITTest {

    @Autowired
    ScriptResourceService service;

    @PersistenceContext
    EntityManager em;

    /** Unique path with the given extension, per-test, to keep the unique {@code path} column collision-free. */
    private static String path(String ext) {
        return "it/srs-" + System.nanoTime() + ext;
    }

    private ScriptResource newResource(String path, String content) {
        ScriptResource r = new ScriptResource();
        r.setPath(path);
        r.setContent(content);
        return r;
    }

    /** Authenticates the current thread as the given username, with the claims a Keycloak token carries. */
    private static void authenticateAs(String username) {
        Jwt jwt = Jwt.withTokenValue("it-token-" + username)
                .header("alg", "none")
                .subject(username)
                .claim("preferred_username", username)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("user")), username));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ create / type derivation

    @Test
    @Transactional
    void createScriptResource_persistsAndDerivesFmTypeFromPath() {
        ScriptResource saved = service.createScriptResource(newResource(path(".ftl"), "Hello ${name}"));
        em.flush();
        em.clear();

        assertNotNull(saved.getId());
        ScriptResource found = em.find(ScriptResource.class, saved.getId());
        assertNotNull(found);
        assertEquals(Type.FM, found.getType());            // type derived from ".ftl"
        assertEquals("Hello ${name}", found.getContent());
    }

    @Test
    @Transactional
    void createScriptResource_derivesJsTypeFromPath() {
        ScriptResource saved = service.createScriptResource(newResource(path(".js"), "var x = 1;"));
        em.flush();
        em.clear();
        assertEquals(Type.JS, em.find(ScriptResource.class, saved.getId()).getType());
    }

    @Test
    @Transactional
    void createScriptResource_duplicatePathRejected() {
        String p = path(".ftl");
        service.createScriptResource(newResource(p, "first"));
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createScriptResource(newResource(p, "second")));
        assertTrue(ex.getMessage().contains("duplicate path"), ex.getMessage());
    }

    // ------------------------------------------------------------------ find

    @Test
    @Transactional
    void findByPath_isCaseInsensitive() {
        ScriptResource saved = service.createScriptResource(newResource("It/Mixed-Case.FTL", "x"));
        em.flush();
        em.clear();

        ScriptResource found = service.findByPath("it/mixed-case.ftl");   // named query lowercases both sides
        assertNotNull(found);
        assertEquals(saved.getId(), found.getId());
    }

    @Test
    @Transactional
    void findByPath_unknownReturnsNull() {
        assertNull(service.findByPath("no/such/path-" + System.nanoTime() + ".ftl"));
    }

    @Test
    @Transactional
    void findById_foundAndMissing() {
        ScriptResource saved = service.createScriptResource(newResource(path(".js"), "1;"));
        em.flush();
        assertEquals(saved.getId(), service.findById(saved.getId()).getId());
        assertNull(service.findById(-999999));
    }

    @Test
    @Transactional
    void findByTypes_filtersByType() {
        ScriptResource fm = service.createScriptResource(newResource(path(".ftl"), "tmpl"));
        ScriptResource js = service.createScriptResource(newResource(path(".js"), "1;"));
        em.flush();

        List<ScriptResource> fmOnly = service.findByTypes(Type.FM);
        assertTrue(fmOnly.stream().anyMatch(r -> r.getId().equals(fm.getId())));
        assertFalse(fmOnly.stream().anyMatch(r -> r.getId().equals(js.getId())));

        List<ScriptResource> both = service.findByTypes(Type.FM, Type.JS);
        assertTrue(both.stream().anyMatch(r -> r.getId().equals(fm.getId())));
        assertTrue(both.stream().anyMatch(r -> r.getId().equals(js.getId())));
    }

    @Test
    @Transactional
    void findAllAndFindAllScriptResourcePaths_returnCreatedRows() {
        ScriptResource a = service.createScriptResource(newResource(path(".ftl"), "a"));
        ScriptResource b = service.createScriptResource(newResource(path(".js"), "b"));
        em.flush();

        List<ScriptResource> all = service.findAll();
        assertTrue(all.stream().anyMatch(r -> r.getId().equals(a.getId())));
        assertTrue(all.stream().anyMatch(r -> r.getId().equals(b.getId())));

        Set<String> paths = service.findAllScriptResourcePaths();
        assertTrue(paths.contains(a.getPath()));
        assertTrue(paths.contains(b.getPath()));
    }

    // ------------------------------------------------------------------ update

    @Test
    @Transactional
    void updateScriptResource_copiesContentPathAndType() {
        ScriptResource saved = service.createScriptResource(newResource(path(".ftl"), "old"));
        em.flush();

        ScriptResource edit = new ScriptResource();
        edit.setId(saved.getId());
        edit.setPath(saved.getPath().replace(".ftl", ".js"));   // change extension -> type recomputed to JS
        edit.setContent("new content");
        ScriptResource updated = service.updateScriptResource(edit);
        em.flush();
        em.clear();

        ScriptResource found = em.find(ScriptResource.class, updated.getId());
        assertEquals("new content", found.getContent());
        assertEquals(Type.JS, found.getType());
    }

    @Test
    @Transactional
    void updateScriptResource_nonExistingRejected() {
        ScriptResource edit = new ScriptResource();
        edit.setId(-123456);
        edit.setPath("x.ftl");
        edit.setContent("y");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateScriptResource(edit));
        assertTrue(ex.getMessage().contains("non-existing"), ex.getMessage());
    }

    // ------------------------------------------------------------------ delete

    @Test
    @Transactional
    void deleteScriptResource_removesResourceAndHistoryAndReturnsTrue() {
        ScriptResource saved = service.createScriptResource(newResource(path(".ftl"), "x"));
        em.flush();
        Integer id = saved.getId();
        assertFalse(service.getScriptResourceHistory(id).isEmpty());   // create wrote a history entry

        boolean deleted = service.deleteScriptResource(id);
        em.flush();
        em.clear();

        assertTrue(deleted);
        assertNull(em.find(ScriptResource.class, id));
        assertTrue(service.getScriptResourceHistory(id).isEmpty());     // history cascaded away
    }

    @Test
    @Transactional
    void deleteScriptResource_unknownIdReturnsFalse() {
        assertFalse(service.deleteScriptResource(-777777));
    }

    // ------------------------------------------------------------------ history

    @Test
    @Transactional
    void saveScriptResource_writesHistorySnapshotPerSave() {
        ScriptResource saved = service.createScriptResource(newResource(path(".ftl"), "v1"));
        em.flush();
        Integer id = saved.getId();
        assertEquals(1, service.getScriptResourceHistory(id).size());

        saved.setContent("v2");
        service.saveScriptResource(saved);
        em.flush();
        assertEquals(2, service.getScriptResourceHistory(id).size());

        // The snapshot is a non-empty gzip-compressed blob.
        assertTrue(service.getScriptResourceHistory(id).stream()
                .allMatch(h -> h.getSnapshot() != null && h.getSnapshot().length > 0));
    }

    // ------------------------------------------------------------------ the importing user

    /** An import runs on a batch thread with no principal, so it names the user its history records */
    @Test
    @Transactional
    void createScriptResource_withImporter_recordsThatUserInHistory() {
        User importer = new User();
        importer.setUsername("it-importer-" + System.nanoTime());
        em.persist(importer);
        em.flush();
        em.detach(importer);   // the batch job hands over a User loaded in another transaction

        ScriptResource saved = service.createScriptResource(newResource(path(".js"), "1;"), importer);
        em.flush();
        em.clear();

        List<ScriptResourceHistory> history = service.getScriptResourceHistory(saved.getId());
        assertEquals(1, history.size());
        assertNotNull(history.get(0).getUser());
        assertEquals(importer.getId(), history.get(0).getUser().getId());
    }

    @Test
    @Transactional
    void createScriptResource_withoutImporter_recordsTheCurrentUserInHistory() {
        User current = new User();
        current.setUsername("it-current-" + System.nanoTime());
        em.persist(current);
        em.flush();
        authenticateAs(current.getUsername());

        ScriptResource saved = service.createScriptResource(newResource(path(".js"), "1;"));
        em.flush();
        em.clear();

        List<ScriptResourceHistory> history = service.getScriptResourceHistory(saved.getId());
        assertEquals(1, history.size());
        assertNotNull(history.get(0).getUser());
        assertEquals(current.getId(), history.get(0).getUser().getId());
    }

    @Test
    @Transactional
    void createScriptResource_withoutImporterOrPrincipal_recordsNoUser() {
        ScriptResource saved = service.createScriptResource(newResource(path(".js"), "1;"));
        em.flush();
        em.clear();

        List<ScriptResourceHistory> history = service.getScriptResourceHistory(saved.getId());
        assertEquals(1, history.size());
        assertNull(history.get(0).getUser());
    }

    @Test
    @Transactional
    void updateScriptResource_withImporter_recordsThatUserInHistory() {
        User importer = new User();
        importer.setUsername("it-importer-" + System.nanoTime());
        em.persist(importer);
        ScriptResource saved = service.createScriptResource(newResource(path(".ftl"), "v1"));
        em.flush();

        saved.setContent("v2");
        service.updateScriptResource(saved, importer);
        em.flush();
        em.clear();

        List<ScriptResourceHistory> history = service.getScriptResourceHistory(saved.getId());
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(h -> h.getUser() != null
                && importer.getId().equals(h.getUser().getId())));
    }

    /**
     * A create body must not carry an id: {@code ScriptResource(ScriptResourceVo)} copies it and
     * {@code saveEntity()} merges a persisted entity, so a "create" with an unused path would silently
     * overwrite the Freemarker/JavaScript resource holding that id. (The batch importer already avoids this
     * by nulling the id - see {@code BatchScriptResourceImportProcessor}.)
     */
    @Test
    @Transactional
    void createScriptResource_rejectsResourceWithExistingId() {
        ScriptResource victim = service.createScriptResource(newResource(path(".ftl"), "victim content"));
        em.flush();
        assertNotNull(victim.getId(), "precondition: the victim resource must have a generated id");

        ScriptResource forged = newResource(path(".ftl"), "hijacked content");
        forged.setId(victim.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createScriptResource(forged));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

}
