/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import jakarta.annotation.security.RolesAllowed;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PathSegmentId;
import org.niord.core.publication.PublicationCategoryDesc;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.core.publication.series.IssueLifecycleService;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The category names this resource writes.
 *
 * ONE resource answers /publication-categories. Its PUT used to ignore the descs
 * entirely, so a category created with a typo could not be renamed through the
 * endpoint being served -- and those names are what the public page shows.
 * Create lives beside update now, with the priority defaulting to the back of
 * the page rather than silently ahead of everything.
 */
public class PublicationCategoryWriteTest {

    private static Method endpoint(String name, Class<?>... params) {
        try {
            return PublicationCategoryRestService.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * The resource with no collaborator at all.
     *
     * PORT: the class takes its category service through a package-private
     * constructor like its three siblings here. The body checks exercised below
     * all refuse before anything is looked up, so the service is never reached.
     */
    private static PublicationCategoryRestService endpointUnderTest() {
        return new PublicationCategoryRestService(null);
    }

    @Test
    public void theUpdateEndpointIsStillAPutAndStillGuarded() {
        Method update = endpoint("update", String.class, Map.class);
        assertNotNull(update);
        // PORT: the verb is @PutMapping under Spring MVC; @RolesAllowed is unchanged.
        assertNotNull(update.getAnnotation(PutMapping.class));
        assertNotNull(update.getAnnotation(RolesAllowed.class));
    }

    /** The names arrive, which update could not previously carry at all. */
    @Test
    public void namesAreApplied() {
        PublicationCategory c = new PublicationCategory();
        PublicationCategoryRestService.applyDescs(c, Map.of("descs", List.of(
                Map.of("lang", "da", "name", "Interne publikationer"),
                Map.of("lang", "en", "name", "Internal publications"))));

        assertEquals(2, c.getDescs().size());
        assertEquals("Interne publikationer", nameOf(c, "da"));
        assertEquals("Internal publications", nameOf(c, "en"));
    }

    /**
     * A second apply REUSES the row for a language rather than rebuilding it.
     *
     * The desc table is unique on (lang, entity) and Hibernate orders inserts
     * before deletes within a flush, so clearing and re-adding "da" collides with
     * the "da" still awaiting deletion. The series model was found failing exactly
     * that way on every edit after the first; this asserts the same mistake was not
     * repeated here.
     */
    @Test
    public void asecondApplyKeepsTheSameDescRow() {
        PublicationCategory c = new PublicationCategory();
        PublicationCategoryRestService.applyDescs(c,
                Map.of("descs", List.of(Map.of("lang", "da", "name", "Foerste"))));
        PublicationCategoryDesc first = c.getDescs().get(0);

        PublicationCategoryRestService.applyDescs(c,
                Map.of("descs", List.of(Map.of("lang", "da", "name", "Anden"))));

        assertEquals(1, c.getDescs().size());
        assertTrue(first == c.getDescs().get(0),
                "the da row was replaced rather than updated; on a managed entity that is an "
                        + "insert racing a delete on a unique key");
        assertEquals("Anden", nameOf(c, "da"));
    }

    /** A language the client stopped sending is a language deleted. */
    @Test
    public void alanguageNoLongerSentIsRemoved() {
        PublicationCategory c = new PublicationCategory();
        PublicationCategoryRestService.applyDescs(c, Map.of("descs", List.of(
                Map.of("lang", "da", "name", "Dansk"),
                Map.of("lang", "en", "name", "English"))));

        PublicationCategoryRestService.applyDescs(c,
                Map.of("descs", List.of(Map.of("lang", "da", "name", "Dansk"))));

        assertEquals(List.of("da"), c.getDescs().stream().map(PublicationCategoryDesc::getLang).toList());
    }

    /** A body with no descs at all leaves the existing names alone. */
    @Test
    public void abodyWithoutDescsChangesNothing() {
        PublicationCategory c = new PublicationCategory();
        PublicationCategoryRestService.applyDescs(c,
                Map.of("descs", List.of(Map.of("lang", "da", "name", "Dansk"))));

        PublicationCategoryRestService.applyDescs(c, Map.of("priority", 10));

        assertEquals(1, c.getDescs().size(), "a partial update erased the names");
        assertEquals("Dansk", nameOf(c, "da"));
    }

    // ------------------------------------------------------- the request guards

    /**
     * The path id wins, and a body that disagrees is refused.
     *
     * categoryId is what every series stores and what an import upserts on, so a
     * rename is not a field edit. Ignoring the mismatch answered 200 with the old
     * id -- the caller believed the rename had happened.
     */
    @Test
    public void arenameThroughTheBodyIsRefusedRatherThanIgnored() {
        var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                () -> endpointUnderTest()
                        .update("dk-nm", Map.of("categoryId", "dk-ntm")));
        assertEquals("CATEGORY_ID_IMMUTABLE", e.code());
        assertEquals(400, PublicationErrorCatalogue.statusOf(e.code()),
                "no change of state makes the same rename correct, so it is not a 409 a client "
                        + "should retry");
    }

    /** A body carrying the SAME id is an ordinary update, not a rename. */
    @Test
    public void abodyRepeatingTheSameIdIsNotARename() {
        // It gets past the guard and fails later for want of the service behind
        // it, which is the point: the guard did not fire.
        assertThrows(Exception.class, () -> endpointUnderTest()
                .update("dk-nm", Map.of("categoryId", "dk-nm")));
    }

    /** An empty body is a coded refusal, not a NullPointerException. */
    @Test
    public void anemptyBodyIsRefusedWithACode() {
        var onUpdate = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                () -> endpointUnderTest().update("dk-nm", null));
        assertEquals("CATEGORY_INVALID", onUpdate.code());

        var onCreate = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                () -> endpointUnderTest().create(null));
        assertEquals("CATEGORY_INVALID", onCreate.code(),
                "an absent body used to dereference null and answer 500, which reads to a client "
                        + "as a server fault rather than as a malformed request");
    }

    /**
     * The anonymous list is ALWAYS bounded.
     *
     * It is externally consumed with no credential at all -- the admin export item
     * is a bare href -- so an absent or zero limit meaning "everything" hands
     * anybody an unbounded read of the table. The parameter name is the shipped
     * contract and is deliberately not the maxSize its sibling uses.
     *
     * PORT: JAX-RS spelled these two facts as separate @QueryParam and
     * @DefaultValue annotations; Spring MVC carries both on one @RequestParam.
     */
    @Test
    public void theAnonymousListCarriesADefaultBound() throws Exception {
        Method all = PublicationCategoryRestService.class
                .getMethod("all", String.class, int.class);
        RequestParam bound = Arrays.stream(all.getParameters())
                // findMergedAnnotation, so that name() and value() are the same fact
                // however the handler happens to spell it
                .map(p -> AnnotatedElementUtils.findMergedAnnotation(p, RequestParam.class))
                .filter(a -> a != null && "limit".equals(a.name()))
                .findFirst().orElse(null);
        assertNotNull(bound, "the parameter is named 'limit' in the shipped contract; renaming it to "
                + "match its sibling breaks the documented export link");
        assertEquals("1000", bound.defaultValue(),
                "the limit lost its default; absent then means unbounded");
    }

    // ------------------------------------- an id the address firewall refuses (PUBCAT2)

    /**
     * Every address that reads, changes or deletes a category carries its id as one path segment, and the request
     * firewall refuses a segment holding these before any handler runs. A category created under such an id could
     * never be opened, changed or deleted, so the create refuses it.
     */
    @Test
    void acreateWithAnIdTheFirewallRefusesIsRefused() {
        for (String id : List.of("a/b", "a\\b", "50%", "a;b", "a\u0007b", "a\tb", "a b", "a b", ".", "..")) {
            var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                    () -> endpointUnderTest().create(named(id, 10)), "the id " + id.replace("\t", "\\t"));
            assertEquals("CATEGORY_INVALID", e.code(), "the id " + id);
        }
    }

    /** Ids the firewall serves are taken, whatever else they hold. */
    @Test
    void acreateWithAnIdTheFirewallServesGoesThrough() {
        for (String id : List.of("dk-nm", "a.b", "...", ".a", "a b", "æøå", "A_1", "x:y", "a?b", "a#b")) {
            PublicationCategory saved = created(named(id, 10));
            assertEquals(id, saved.getCategoryId(), "the id " + id);
        }
    }

    /** The rule itself: exactly what the firewall refuses, no slug pattern. */
    @Test
    void theIdRuleRefusesExactlyWhatTheFirewallCannotServe() {
        for (String id : List.of("/", "a/", "\\x", "%2F", ";", "\u0000", "\n", "\r", "\u007f", "\u0085", " ",
                " ", ".", "..")) {
            assertNotNull(PathSegmentId.unservableReason(id), "refused: " + id);
        }
        for (String id : List.of("dk-nm", "a.b", "...", ". ", " ..", "æøå", "x:y", "a b", "🙂")) {
            assertEquals(null, PathSegmentId.unservableReason(id), "served: " + id);
        }
        assertEquals(null, PathSegmentId.unservableReason(null), "a missing id is the caller's own rule");
    }

    // ------------------------------------------------------- a name in some language (PUB4)

    /** A create that leaves the category with no name in any language is refused, with no descs as with blank ones. */
    @Test
    void acreateWithoutANameIsRefused() {
        Map<String, Object> noDescs = new java.util.LinkedHashMap<>(Map.of("categoryId", "dk-nm", "priority", 10));
        for (Map<String, Object> body : List.of(
                noDescs,
                body("dk-nm", 10, List.of()),
                body("dk-nm", 10, List.of(desc("da", ""), desc("en", "   "))),
                body("dk-nm", 10, List.of(Map.of("lang", "en"))))) {
            var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                    () -> endpointUnderTest().create(body), "the body " + body);
            assertEquals("CATEGORY_INVALID", e.code(), "the body " + body);
        }
    }

    /** One non-blank name in one language is enough: a half-translated category is still saved. */
    @Test
    void acreateWithANameInOneLanguageGoesThrough() {
        PublicationCategory saved = created(body("dk-nm", 10, List.of(desc("da", "Danske"), desc("en", " "))));
        assertEquals("Danske", nameOf(saved, "da"));
    }

    /** An update that leaves no name is refused; one that leaves the stored names alone is not. */
    @Test
    void anupdateThatLeavesNoNameIsRefused() {
        for (List<Map<String, Object>> descs : List.<List<Map<String, Object>>>of(
                List.of(), List.of(desc("da", " ")), List.of(desc("da", null)))) {
            var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                    () -> withStored(stored("dk-nm")).update("dk-nm", body("dk-nm", 10, descs)),
                    "the descs " + descs);
            assertEquals("CATEGORY_INVALID", e.code(), "the descs " + descs);
        }
        // No descs in the body: the stored names stay, so the save goes through
        assertEquals(20, ((Number) withStored(stored("dk-nm"))
                .update("dk-nm", new java.util.LinkedHashMap<>(Map.of("priority", 20))).get("priority")).intValue());
    }

    /** A category already stored without a name cannot be saved again until it is named. */
    @Test
    void anupdateOfACategoryStoredWithoutANameMustName() {
        PublicationCategory nameless = new PublicationCategory();
        nameless.setCategoryId("dk-nm");
        var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                () -> withStored(nameless).update("dk-nm", new java.util.LinkedHashMap<>(Map.of("priority", 20))));
        assertEquals("CATEGORY_INVALID", e.code());
        Map<String, Object> named = withStored(nameless).update("dk-nm", body("dk-nm", 20, List.of(desc("en", "Named"))));
        assertEquals("dk-nm", named.get("categoryId"));
    }

    // ---------------------------------------------- Order a whole number in range (PUBCAT3)

    /** A priority past the int range or with a fraction used to be stored wrapped or cut without a word. */
    @Test
    void apriorityThatIsNoWholeNumberInRangeIsRefusedOnCreateAndUpdate() {
        for (Object priority : List.<Object>of(3_000_000_000L, 2_147_483_648L, -2_147_483_649L, 1.7, -0.5, 1e10,
                new java.math.BigInteger("100000000000000000000"), new java.math.BigDecimal("2.5"), "100", true,
                Map.of(), List.of(1))) {
            Map<String, Object> body = body("dk-nm", 10, List.of(desc("en", "Name")));
            body.put("priority", priority);
            var onCreate = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                    () -> endpointUnderTest().create(body), "create with " + priority);
            assertEquals("CATEGORY_INVALID", onCreate.code(), "create with " + priority);
            var onUpdate = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                    () -> withStored(stored("dk-nm")).update("dk-nm", body), "update with " + priority);
            assertEquals("CATEGORY_INVALID", onUpdate.code(), "update with " + priority);
        }
    }

    /** Whole numbers in range are taken however JSON spelled them; an absent priority keeps its default or value. */
    @Test
    void apriorityThatIsAWholeNumberInRangeIsStored() {
        Map<Object, Integer> taken = new java.util.LinkedHashMap<>();
        taken.put(7, 7);
        taken.put(7L, 7);
        taken.put(7.0, 7);
        taken.put(-0.0, 0);
        taken.put((short) 3, 3);
        taken.put(new java.math.BigInteger("2147483647"), Integer.MAX_VALUE);
        taken.put(2_147_483_647L, Integer.MAX_VALUE);
        taken.put(-2_147_483_648L, Integer.MIN_VALUE);
        taken.put(new java.math.BigDecimal("12.000"), 12);
        taken.forEach((sent, expected) -> {
            Map<String, Object> body = body("dk-nm", 0, List.of(desc("en", "Name")));
            body.put("priority", sent);
            assertEquals(expected, created(body).getPriority(), "create with " + sent);
            assertEquals(expected,
                    ((Number) withStored(stored("dk-nm")).update("dk-nm", body).get("priority")).intValue(),
                    "update with " + sent);
        });

        Map<String, Object> absent = body("dk-nm", 0, List.of(desc("en", "Name")));
        absent.remove("priority");
        assertEquals(PublicationCategoryRestService.DEFAULT_PRIORITY, created(absent).getPriority(),
                "an absent priority on create lands at the back");
    }

    /**
     * A cleared Order arrives as an explicit null. The update used to keep the stored priority and answer as if the
     * change were saved (L-173); it is refused now, and only an absent key keeps the stored priority.
     */
    @Test
    void anupdateWithANullPriorityIsRefusedAndOneWithoutItKeepsTheStoredPriority() {
        PublicationCategory c = stored("dk-nm");
        Map<String, Object> cleared = named("dk-nm", 0);
        cleared.put("priority", null);
        var e = assertThrows(IssueLifecycleService.TransitionRefusedException.class,
                () -> withStored(c).update("dk-nm", cleared));
        assertEquals("CATEGORY_INVALID", e.code());
        assertEquals(50, c.getPriority(), "the refused update left the stored priority");

        Map<String, Object> absent = named("dk-nm", 0);
        absent.remove("priority");
        assertEquals(50, ((Number) withStored(c).update("dk-nm", absent).get("priority")).intValue(),
                "a body without a priority keeps the stored one");
    }

    // ------------------------------------------------------------ helpers for the checks above

    private static Map<String, Object> named(String id, int priority) {
        return body(id, priority, List.of(desc("en", "Name")));
    }

    private static Map<String, Object> body(String id, int priority, List<Map<String, Object>> descs) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("categoryId", id);
        body.put("priority", priority);
        body.put("descs", descs);
        return body;
    }

    private static Map<String, Object> desc(String lang, String name) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("lang", lang);
        d.put("name", name);
        return d;
    }

    /** A stored category with a name in Danish and English and priority 50. */
    private static PublicationCategory stored(String id) {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(id);
        c.setPriority(50);
        PublicationCategoryRestService.applyDescs(c, Map.of("descs", List.of(desc("da", "Dansk"), desc("en", "English"))));
        return c;
    }

    /** The resource over a service that finds the given category and saves what it is given. */
    private static PublicationCategoryRestService withStored(PublicationCategory c) {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        when(service.requireByCategoryId(c.getCategoryId())).thenReturn(c);
        when(service.save(any())).thenAnswer(a -> a.getArgument(0));
        return new PublicationCategoryRestService(service);
    }

    /** What a create hands the service, through a service that stores what it is given. */
    private static PublicationCategory created(Map<String, Object> body) {
        PublicationCategoryService service = mock(PublicationCategoryService.class);
        when(service.createUnderNewId(any())).thenAnswer(a -> a.getArgument(0));
        new PublicationCategoryRestService(service).create(body);
        org.mockito.ArgumentCaptor<PublicationCategory> captor =
                org.mockito.ArgumentCaptor.forClass(PublicationCategory.class);
        verify(service).createUnderNewId(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------ the read shape

    /**
     * A category with no name in the requested language still answers with one.
     *
     * Falling back is what every other localized read in the product does. An
     * empty descs array left the series form's dropdown rendering the raw slug,
     * and the still-shipped admin list throwing on descs[0].name.
     */
    @Test
    public void arequestForAnAbsentLanguageFallsBackToWhatTheCategoryHas() {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId("dk-nm");
        PublicationCategoryRestService.applyDescs(c,
                Map.of("descs", List.of(Map.of("lang", "da", "name", "Danske publikationer"))));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> descs =
                (List<Map<String, String>>) PublicationCategoryRestService.toMap(c, "en").get("descs");

        assertEquals(1, descs.size(), "the fallback returned nothing to render a label from");
        assertEquals("Danske publikationer", descs.get(0).get("name"));
    }

    /** And the requested language wins where the category has it. */
    @Test
    public void therequestedLanguageIsTheOneReturned() {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId("dk-nm");
        PublicationCategoryRestService.applyDescs(c, Map.of("descs", List.of(
                Map.of("lang", "da", "name", "Danske publikationer"),
                Map.of("lang", "en", "name", "Danish publications"))));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> descs =
                (List<Map<String, String>>) PublicationCategoryRestService.toMap(c, "en").get("descs");

        assertEquals(1, descs.size());
        assertEquals("Danish publications", descs.get(0).get("name"));
    }

    private static String nameOf(PublicationCategory c, String lang) {
        return c.getDescs().stream()
                .filter(d -> lang.equals(d.getLang()))
                .map(PublicationCategoryDesc::getName)
                .findFirst().orElse(null);
    }
}
