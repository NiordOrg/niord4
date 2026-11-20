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
package org.niord.core.category;

import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.aton.AtonFilter;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.bootstrap.Given;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.script.ScriptResource;
import org.niord.core.service.TreeBaseService;
import org.niord.core.settings.Setting;
import org.niord.core.web.NiordWebException;
import org.niord.model.search.PagedSearchParamsVo;
import org.slf4j.Logger;

import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.NoResultException;
import jakarta.persistence.criteria.*;
import javax.script.ScriptException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Business interface for accessing Niord categories
 */
@Service
@SuppressWarnings("unused")
public class CategoryService extends TreeBaseService<Category> {

    /** When the category tree's sort order was last recomputed: a value the system writes, never an operator */
    public static final Setting CATEGORY_LAST_UPDATE = new Setting("categoryLastUpdate", null, false)
            .type(Setting.Type.Date)
            .scope(Setting.Scope.state)
            .description("When the category tree's sort order was last recomputed");


    private static final Logger log = org.slf4j.LoggerFactory.getLogger(CategoryService.class);

    private final DomainService domainService;

    private final DomainLookupService domainLookupService;

    private final NiordApp app;

    CategoryService(DomainService domainService, DomainLookupService domainLookupService, NiordApp app) {
        this.domainService = domainService;
        this.domainLookupService = domainLookupService;
        this.app = app;
    }

    /***************************************/
    /** Category look-up                  **/
    /***************************************/

    /**
     * Returns the category with the given legacy id
     * <p>
     * The legacy id is not unique in the schema: when several categories have it (restored data), none is returned,
     * as when there is none, and a WARN names the legacy id.
     *
     * @param legacyId the id of the category
     * @return the category with the given id or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public Category findByLegacyId(String legacyId) {
        try {
            return em.createNamedQuery("Category.findByLegacyId", Category.class)
                    .setParameter("legacyId", legacyId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (NonUniqueResultException e) {
            log.warn("Several categories have the legacy id '{}'; the look-up by legacy id answers none of them",
                    legacyId);
            return null;
        }
    }

    /**
     * Searches for categories matching the given term in the given language
     *
     * @param params the sesarch params
     * @return the search result
     */
    @SuppressWarnings("all")
    public List<Category> searchCategories(CategorySearchParams params) {

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Category> categoryQuery = cb.createQuery(Category.class);

        Root<Category> categoryRoot = categoryQuery.from(Category.class);

        // Build the predicate
        CriteriaHelper<Category> criteriaHelper = new CriteriaHelper<>(cb, categoryQuery);

        // Match the name
        Join<Category, CategoryDesc> descs = categoryRoot.join("descs", JoinType.LEFT);
        if (params.isExact()) {
            criteriaHelper.equalsIgnoreCase(descs.get("name"), params.getName());
        } else  {
            criteriaHelper.like(descs.get("name"), params.getName());
        }

        // Optionally, filter by type
        criteriaHelper.equals(categoryRoot.get("type"), params.getType());

        // Optionally, match the language
        if (StringUtils.isNotBlank(params.getLanguage())) {
            criteriaHelper.equals(descs.get("lang"), params.getLanguage());
        }

        // Optionally, match the parent
        if (params.getParentId() != null) {
            categoryRoot.join("parent", JoinType.LEFT);
            Path<Category> parent = categoryRoot.get("parent");
            criteriaHelper.equals(parent.get("id"), params.getParentId());
        } else if (params.isRootOnly()) {
            criteriaHelper.add(cb.isNull(categoryRoot.get("parent")));
        }

        // Optionally, match any ancestor
        if (params.getAncestorId() != null) {
            Category ancestor = getCategoryDetails(params.getAncestorId());
            if (ancestor != null) {
                criteriaHelper.add(cb.like(categoryRoot.get("lineage"), ancestor.getLineage() + "%/"));
            }
        }

        // Optionally, filter by domain
        if (StringUtils.isNotBlank(params.getDomain())) {

            Domain d = domainLookupService.findByDomainId(params.getDomain());

            // First, filter by categories associated with the current domain
            if (d != null && d.getCategories().size() > 0) {
                Predicate[] categoryMatch = d.getCategories().stream()
                        .map(c -> cb.like(categoryRoot.get("lineage"), c.getLineage() + "%"))
                        .toArray(Predicate[]::new);
                criteriaHelper.add(cb.or(categoryMatch));
            }

            // Next, filter template messages by their associated list of domains
            if (d != null) {
                Join<Category, Domain> domains = categoryRoot.join("domains", JoinType.LEFT);
                criteriaHelper.add(cb.or(
                        cb.isEmpty(categoryRoot.get("domains")), // Either no domains specified
                        cb.equal(domains.get("domainId"), d.getDomainId()) // or domain in domain list
                ));
            }
        }

        // Unless the "inactive" search flag is set, only include active categories.
        if (!params.isInactive()) {
            criteriaHelper.add(cb.equal(categoryRoot.get("active"), true));
        }

        // Compute the sort order
        List<Order> sortOrders = new ArrayList<>();
        if (CategorySearchParams.TREE_SORT_ORDER.equals(params.getSortBy())) {
            Arrays.asList("treeSortOrder", "siblingSortOrder", "id")
                    .forEach(field -> {
                        if (params.getSortOrder() == PagedSearchParamsVo.SortOrder.ASC) {
                            sortOrders.add(cb.asc(categoryRoot.get(field)));
                        } else {
                            sortOrders.add(cb.desc(categoryRoot.get(field)));
                        }
                    });
        }

        // Complete the query
        categoryQuery.select(categoryRoot)
                .distinct(true)
                .where(criteriaHelper.where())
                .orderBy(sortOrders);

        // Execute the query and update the search result
        List<Category> categories = em.createQuery(categoryQuery)
                .getResultList();

        // Optionally, filter on AtoNs
        // NB: Expensive!
        if (params.getAtons() != null && !params.getAtons().isEmpty()) {
            categories = resolveAtonCategories(categories, params.getAtons(), params.getMaxSize());
        }

        return categories.stream()
                .limit(params.getMaxSize())
                .toList();
    }

    /**
     * Returns the hierarchical list of root categories.
     * <p>
     * @return the hierarchical list of root categories
     */
    public List<Category> getCategoryTree() {
        return getTree(Category.class, "Category.findCategoriesWithDescs");
    }

    /** {@inheritDoc} **/
    @Override
    public List<Category> getRootEntities() {
        return em.createNamedQuery("Category.findRootCategories", Category.class)
                .getResultList();
    }

    /**
     * Looks up an category
     *
     * @param id the id of the category
     * @return the category
     */
    public Category getCategoryDetails(Integer id) {
        return getByPrimaryKey(Category.class, id);
    }

    /**
     * Looks up the categories with the given IDs
     *
     * @param ids the ids of the category
     * @return the category
     */
    public List<Category> getCategoryDetails(List<Integer> ids) {
        return em.createNamedQuery("Category.findCategoriesWithIds", Category.class)
                .setParameter("ids", ids)
                .getResultList().stream()
                .sorted(Comparator.comparingInt(c -> ids.indexOf(c.getId())))
                .toList();
    }

    /***************************************/
    /** Category life-cycles              **/
    /***************************************/

    /**
     * Updates the category data from the category template, but not the parent-child hierarchy of the category
     *
     * @param category the category to update
     * @return the updated category
     */
    public Category updateCategoryData(Category category) {
        Category original = requireByPrimaryKey(Category.class, category.getId());
        return updateCategoryData(original, category);
    }

    /**
     * Updates the category data from the category template, but not the parent-child hierarchy of the category
     *
     * @param original the original category to update
     * @param category the template category to update the original with
     * @return the updated category
     */
    @Transactional
    public Category updateCategoryData(Category original, Category category) {

        original.setType(category.getType());
        original.setMrn(category.getMrn());
        original.setActive(category.isActive());
        original.setSiblingSortOrder(category.getSiblingSortOrder());
        original.copyDescsAndRemoveBlanks(category.getDescs());
        original.getEditorFields().clear();
        original.getEditorFields().addAll(category.getEditorFields());
        original.setAtonFilter(category.getAtonFilter());

        original.updateLineage();
        original.updateActiveFlag();

        original.getScriptResourcePaths().clear();
        category.getScriptResourcePaths().stream()
                .filter(p -> ScriptResource.path2type(p) != null)
                .forEach(p -> original.getScriptResourcePaths().add(p));
        original.setMessageId(category.getMessageId());

        // Replace domains with persisted entities
        original.setDomains(domainService.persistedDomains(category.getDomains()));

        original.getStdTemplateFields().clear();
        original.getStdTemplateFields().addAll(category.getStdTemplateFields());

        original.getTemplateParams().clear();
        original.getTemplateParams().addAll(category.getTemplateParams());

        return saveEntity(original);
    }

    /**
     * If data has changed, updates the category data from the category template,
     * but not the parent-child hierarchy of the category
     *
     * @param original the original category to update
     * @param category the template category to update the original with
     * @return the updated category
     */
    public Category checkUpdateCategoryData(Category original, Category category) {
        if (original.hasChanged(category)) {
            return updateCategoryData(original, category);
        }
        return original;
    }

    /**
     * Creates a new category based on the category template
     * @param category the category to create
     * @param parentId the id of the parent category
     * @return the created category
     */
    @Transactional
    public Category createCategory(Category category, Integer parentId) {

        // Reset the ID of the category: this is a create, and saveEntity() MERGES an entity that carries one -
        // silently overwriting whatever row holds that id. The template legitimately arrives with a foreign id
        // via importCategory() (the category-import batch job, and the remote-message importer), which matches
        // on name and never on id, so dropping it here costs nothing and closes the merge for every caller.
        category.setId(null);

        // Replace domains with persisted entities, before the category is reachable from its parent: the domain
        // queries' auto-flush must not meet the request's transient domains through the parent's children
        category.setDomains(domainService.persistedDomains(category.getDomains()));

        if (parentId != null) {
            // An unknown parent is refused as the move refuses it: 404 "No Category with id …"
            Category parent = requireByPrimaryKey(Category.class, parentId);
            parent.addChild(category);
        }

        category = saveEntity(category);

        // The category now has an ID - Update lineage
        category.updateLineage();
        category.updateActiveFlag();
        category = saveEntity(category);

        em.flush();
        return category;
    }

    /**
     * Moves the category to the given parent id
     * @param categoryId the id of the category to create
     * @param parentId the id of the parent category
     * @return if the category was moved
     */
    public boolean moveCategory(Integer categoryId, Integer parentId) {
        return moveEntity(Category.class, categoryId, parentId);
    }

    /**
     * Changes the sort order of an category, by moving it up or down compared to siblings.
     * <p>
     * Please note that by moving "up" we mean in a geographical tree structure,
     * i.e. a smaller sortOrder value.
     *
     * @param categoryId the id of the category to move
     * @param moveUp whether to move the category up or down
     * @return if the category was moved
     */
    public boolean changeSortOrder(Integer categoryId, boolean moveUp) {
        return changeSortOrder(Category.class, categoryId, moveUp);
    }

    /**
     * Deletes the category and sub-categories.
     * <p>
     * A category is refused with a 409 when it or one of its sub-categories is in use: named by a message or a domain.
     * The detail names the uses, so the administrator knows what to take out of use first, or can make the category
     * inactive instead.
     *
     * @param categoryId the id of the category to delete
     * @return true when the category was deleted, false when no category has the id
     */
    @Transactional
    public boolean deleteCategory(Integer categoryId) {

        Category category = getByPrimaryKey(Category.class, categoryId);
        if (category != null) {
            refuseDeleteOfCategoryInUse(category);

            // Remove parent category relation, from both sides: the parent's children cascade ALL, and a category left
            // in them is saved again at the flush, as a root, instead of being deleted
            if (category.getParent() != null) {
                category.getParent().getChildren().remove(category);
            }
            category.setParent(null);
            saveEntity(category);
            remove(category);
            log.debug("Removed category " + categoryId);
            return true;
        }
        return false;
    }

    /**
     * Refuses the delete of a category when it or one of its sub-categories is in use. The foreign keys to a category
     * are NO ACTION, so without this check the delete fails at the flush and answers 500 with no reason.
     *
     * @param category the category to delete
     */
    private void refuseDeleteOfCategoryInUse(Category category) {
        // The ids of the category and every category under it: the rows the delete removes, by the children's cascade
        Set<Integer> ids = new HashSet<>();
        Deque<Category> todo = new ArrayDeque<>(List.of(category));
        while (!todo.isEmpty()) {
            Category c = todo.pop();
            if (c.getId() != null && ids.add(c.getId())) {
                todo.addAll(c.getChildren());
            }
        }

        long messages = em.createQuery("SELECT count(DISTINCT m.id) FROM Message m JOIN m.categories c "
                        + "WHERE c.id IN :ids", Long.class)
                .setParameter("ids", ids)
                .getSingleResult();
        List<String> domains = em.createQuery("SELECT DISTINCT d.domainId FROM Domain d JOIN d.categories c "
                        + "WHERE c.id IN :ids ORDER BY d.domainId", String.class)
                .setParameter("ids", ids)
                .getResultList();

        List<String> uses = new ArrayList<>();
        if (messages > 0) {
            uses.add(messages + (messages == 1 ? " message" : " messages"));
        }
        if (!domains.isEmpty()) {
            uses.add((domains.size() == 1 ? "the domain " : "the domains ") + String.join(", ", domains));
        }
        if (!uses.isEmpty()) {
            throw new NiordWebException("The category or one of its sub-categories is in use: " + String.join("; ", uses)
                    + ". Make it inactive instead, or take it out of use first.", 409);
        }
    }

    /**
     * Looks up an category by name
     * @param name the name to search for
     * @param lang the language. Optional
     * @param parentId the parent ID. Optional
     * @return The matching category, or null if not found
     */
    public Category findByName(String name, String lang, Integer parentId) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        CategorySearchParams params = new CategorySearchParams();
        params.parentId(parentId)
                .language(lang)
                .inactive(true) // Also search inactive categorys
                .name(name)
                .exact(true) // Not substring matches
                .maxSize(1);

        List<Category> categories = searchCategories(params);

        return categories.isEmpty() ? null : categories.get(0);
    }

    /**
     * Looks up a root category (one without a parent) by name
     * @param name the name to search for
     * @param lang the language. Optional
     * @return The matching root category, or null if not found
     */
    public Category findRootByName(String name, String lang) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        CategorySearchParams params = new CategorySearchParams();
        params.rootOnly(true)
                .language(lang)
                .inactive(true) // Also search inactive categories
                .name(name)
                .exact(true) // Not substring matches
                .maxSize(1);

        List<Category> categories = searchCategories(params);

        return categories.isEmpty() ? null : categories.get(0);
    }

    /**
     * Returns the category with the given MRN. Returns null if the category is not found.
     * <p>
     * The MRN is matched without regard to case, exact match first: the category whose MRN is exactly the given one,
     * and only when there is none, the category whose MRN differs from it only in case.
     * <p>
     * The MRN is not unique in the schema: when several categories have it (restored data), none is returned, as
     * when there is none, and a WARN names the MRN. So it is when no category has the MRN exactly and several have it
     * in different case, and the WARN names theirs.
     *
     * @param mrn the MRN of the category
     * @return the category with the given MRN or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public Category findByMrn(String mrn) {
        List<Category> exact = em.createNamedQuery("Category.findByMrn", Category.class)
                .setParameter("mrn", mrn)
                .getResultList();
        if (exact.size() > 1) {
            log.warn("Several categories have the MRN '{}'; the look-up by MRN answers none of them", mrn);
            return null;
        }
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        List<Category> folded = em.createNamedQuery("Category.findByMrnIgnoreCase", Category.class)
                .setParameter("mrn", mrn)
                .getResultList();
        if (folded.size() > 1) {
            log.warn("No category has the MRN '{}' exactly and several have it in different case ({}); the look-up by "
                    + "MRN answers none of them", mrn, folded.stream()
                    .map(c -> "'" + c.getMrn() + "' (id " + c.getId() + ")")
                    .collect(Collectors.joining(", ")));
            return null;
        }
        return folded.isEmpty() ? null : folded.getFirst();
    }

    /**
     * Returns the category with the given ID or MRN. Returns null if the category is not found.
     *
     * @param categoryId the ID or MRN of the category
     * @return the category with the given ID or MRN or null if not found
     */
    public Category findByCategoryId(String categoryId) {
        if (StringUtils.isNumeric(categoryId)) {
            return getCategoryDetails(Integer.valueOf(categoryId));
        }
        return findByMrn(categoryId);
    }

    /**
     * Ensures that the template category and it's parents exists
     *
     * @param templateCategory the template category
     * @param create whether to create a missing category or just find it
     * @param update whether to update an existing category or just find it
     * @return the category
     */
    public Category importCategory(Category templateCategory, boolean create, boolean update) {
        return importCategory(templateCategory, create, update, BootstrapRun.Scope.NONE, a -> { });
    }

    /**
     * Ensures that the template category and it's parents exists, within the scope of a bootstrap job: within the
     * first bootstrap's rule, the values a later archive gives replace those of a category an earlier archive of the
     * run created ({@link #replaceInCategory}), and each category created here is recorded for the run's later
     * archives ({@link BootstrapRun}). Each such category, a parent included, is reported, and so is one an earlier
     * archive created inactive that a new active child makes active ({@code TreeBaseEntity.updateActiveFlag}). Outside
     * a run ({@link BootstrapRun.Scope#NONE}) it is {@link #importCategory(Category, boolean, boolean)} and reports
     * nothing.
     * <p>
     * The template's domains are replaced with the stored domains of the same ids, first: a domain this Niord does not
     * have is left out of the template, and so of the category.
     *
     * @param templateCategory the template category
     * @param create whether to create a missing category or just find it
     * @param update whether to update an existing category or just find it
     * @param scope the bootstrap run's scope of the job
     * @param report receives what was added, replaced and kept of each category an earlier archive of the run created
     * @return the category
     */
    public Category importCategory(Category templateCategory, boolean create, boolean update,
                                   BootstrapRun.Scope scope, Consumer<BootstrapRun.Additions> report) {
        return importCategory(templateCategory, create, update, scope, report, false);
    }

    /**
     * As {@link #importCategory(Category, boolean, boolean, BootstrapRun.Scope, Consumer)}, for a template that may
     * carry its whole chain of parents.
     * <p>
     * A template without a parent is matched by name at any depth unless {@code wholeChain} is set: a message's or a
     * domain's category is read without its parent ({@code new Message(vo)}, {@code new Domain(vo)}), so its name alone
     * must find the nested category it names. An entry of a category file carries its parents
     * ({@code BatchCategoryImportProcessor}), so there a template without a parent is a root and matches a root only,
     * never a category of the same name deeper in the tree. A template with a parent is matched under that parent
     * either way.
     *
     * @param templateCategory the template category
     * @param create whether to create a missing category or just find it
     * @param update whether to update an existing category or just find it
     * @param scope the bootstrap run's scope of the job
     * @param report receives what was added, replaced and kept of each category an earlier archive of the run created
     * @param wholeChain whether the template carries its whole chain of parents, so that one without a parent is a root
     * @return the category
     */
    public Category importCategory(Category templateCategory, boolean create, boolean update,
                                   BootstrapRun.Scope scope, Consumer<BootstrapRun.Additions> report,
                                   boolean wholeChain) {
        // Sanity checks
        if (templateCategory == null) {
            return null;
        }

        // Replace the template's domains with the stored ones, before any lookup and on every path. A domain this
        // Niord does not have is left out: domain ids differ between deployments, and keeping the file's transient
        // object fails the save. The category-import job names what was left out in its log
        // (BatchCategoryImportProcessor compares the file's domain ids with the template's after this call)
        if (templateCategory.getDomains() != null && !templateCategory.getDomains().isEmpty()) {
            templateCategory.setDomains(templateCategory
                    .getDomains()
                    .stream()
                    .filter(Objects::nonNull)
                    .map(Domain::getDomainId)
                    .filter(Objects::nonNull)
                    .map(this.domainLookupService::findByDomainId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }

        // Check if we can find the category by MRN
        if (StringUtils.isNotBlank(templateCategory.getMrn())) {
            Category category = findByMrn(templateCategory.getMrn());
            if (category != null) {
                return update ? updateFound(category, templateCategory, scope, report) : category;
            }
        }

        // Recursively, resolve the parent categories
        Category parent = null;
        if (templateCategory.getParent() != null) {
            parent = importCategory(templateCategory.getParent(), create, update, scope, report, wholeChain);
            if (!create && parent == null) {
                return null;
            }
        }
        Integer parentId = (parent == null) ? null : parent.getId();

        // Check if we can find the given category, under the same parent. A template without a parent matches a root
        // only when it carries its whole chain (a category file's entry); otherwise by name at any depth (CATEGORY2)
        Category category = null;
        for (int x = 0; category == null && x < templateCategory.getDescs().size(); x++) {
            CategoryDesc desc = templateCategory.getDescs().get(x);
            category = parentId == null && wholeChain
                    ? findRootByName(desc.getName(), desc.getLang())
                    : findByName(desc.getName(), desc.getLang(), parentId);
        }

        // Create the category if no matching category was found
        if (create && category == null) {
            // An earlier archive's inactive ancestor that the new child's active flag will make active
            List<Category> inactive = new ArrayList<>();
            for (Category a = parent; a != null; a = a.getParent()) {
                if (!a.isActive() && scope.createdEarlier(BootstrapRun.Kind.CATEGORY, String.valueOf(a.getId()))) {
                    inactive.add(a);
                }
            }
            category = createCategory(templateCategory, parentId);
            scope.created(BootstrapRun.Kind.CATEGORY, String.valueOf(category.getId()));
            for (Category a : inactive) {
                if (a.isActive()) {
                    report.accept(new BootstrapRun.Additions(entry(a), List.of(), List.of(), List.of(),
                            List.of("activated by its new active child " + category.getId()
                                    + " (TreeBaseEntity.updateActiveFlag; not covered by the rule)")));
                }
            }
        } else if (update && category != null) {
            category = updateFound(category, templateCategory, scope, report);
        }
        return category;
    }

    /**
     * Updates a category the import found, or, when an earlier archive of the bootstrap run created it, replaces in it
     * the values the later archive gives
     */
    private Category updateFound(Category category, Category templateCategory, BootstrapRun.Scope scope,
                                 Consumer<BootstrapRun.Additions> report) {
        return scope.createdEarlier(BootstrapRun.Kind.CATEGORY, String.valueOf(category.getId()))
                ? replaceInCategory(category, templateCategory, templateCategory.getGiven(), report)
                : checkUpdateCategoryData(category, templateCategory);
    }

    /** A category as the job log names it: its id and its English name, else its first */
    private static String entry(Category category) {
        CategoryDesc desc = category.getDesc("en") != null ? category.getDesc("en")
                : category.getDescs().isEmpty() ? null : category.getDescs().get(0);
        return "Category " + category.getId() + (desc != null ? " '" + desc.getName() + "'" : "");
    }

    /**
     * Replaces in a category an earlier archive of the bootstrap run created the values the later archive's category
     * gives, within the first bootstrap's rule (docs/code-data-config-plan.md §7.2): the type, the active flag, the
     * sort order and the AtoN filter, when the file gives them ({@code given}) and they differ; the MRN, a matching
     * key, only when the category has none; a name in a language the category has, and a language it lacks is added;
     * the template parameters ({@link TemplateExecutionService#replaceTemplateParams}); the standard fields, the editor
     * fields and the script paths, when the file gives them and they differ, an explicit empty list emptying them; and
     * the domains it is not linked to are added. A value the file leaves out is kept, no name, template parameter or
     * domain link is removed, and the message is outside the rule. Every write compares first: a name or a template
     * parameter is changed in place, a list only where it differs, and an entry equal in every value is not written.
     *
     * <p>What was added and replaced is reported for the job log, and what the file gives that cannot change: a
     * different MRN, and a domain no row carries ("not found"; through {@link #importCategory} the template has none
     * left, since the import leaves such a domain out first, and the category-import job names it in a line of its
     * own). A changed active flag is carried through the tree as
     * {@code TreeBaseEntity.updateActiveFlag} carries it, outside the rule: made inactive, the category makes its
     * active descendants inactive, and made active, its inactive ancestors active; the line names each of them.
     *
     * @param original the category, as an earlier archive of the run created it
     * @param template the later archive's category
     * @param given    which fields the later archive's item gives
     * @param report   receives what was added, replaced and kept
     * @return the category
     * @throws IllegalStateException when there is no {@code given}: the job fails
     */
    @Transactional
    public Category replaceInCategory(Category original, Category template, Given given,
                                      Consumer<BootstrapRun.Additions> report) {
        if (given == null) {
            throw new IllegalStateException(entry(original) + ": the first bootstrap's rule needs to know which "
                    + "fields the later archive gives, and the reader set none");
        }
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        List<String> kept = new ArrayList<>();

        if (given.has("type") && template.getType() != null && template.getType() != original.getType()) {
            replaced.add(BootstrapRun.Additions.change("the type", original.getType(), template.getType()));
            original.setType(template.getType());
        }
        boolean activeChanged = given.has("active") && template.isActive() != original.isActive();
        if (activeChanged) {
            replaced.add(BootstrapRun.Additions.change("the active flag", original.isActive(), template.isActive()));
            original.setActive(template.isActive());
        }
        if (given.has("siblingSortOrder")
                && Double.compare(template.getSiblingSortOrder(), original.getSiblingSortOrder()) != 0) {
            replaced.add(BootstrapRun.Additions.change("the sort order", original.getSiblingSortOrder(),
                    template.getSiblingSortOrder()));
            original.setSiblingSortOrder(template.getSiblingSortOrder());
        }
        if (given.has("atonFilter") && !Objects.equals(original.getAtonFilter(), template.getAtonFilter())) {
            replaced.add(BootstrapRun.Additions.change("the AtoN filter", original.getAtonFilter(),
                    template.getAtonFilter()));
            original.setAtonFilter(template.getAtonFilter());
        }
        if (given.has("mrn") && !Objects.equals(original.getMrn(), template.getMrn())) {
            if (StringUtils.isBlank(original.getMrn())) {
                replaced.add(BootstrapRun.Additions.change("the MRN", original.getMrn(), template.getMrn()));
                original.setMrn(template.getMrn());
            } else {
                kept.add("the MRN (a matching key)");
            }
        }

        for (CategoryDesc desc : template.getDescs()) {
            if (StringUtils.isBlank(desc.getLang()) || StringUtils.isBlank(desc.getName())) {
                continue;
            }
            CategoryDesc existing = original.getDesc(desc.getLang());
            if (existing == null) {
                original.checkCreateDesc(desc.getLang()).copyDesc(desc);
                added.add(desc.getLang());
            } else if (!Objects.equals(existing.getName(), desc.getName())) {
                replaced.add(BootstrapRun.Additions.change("the " + desc.getLang() + " name", existing.getName(),
                        desc.getName()));
                existing.setName(desc.getName());
            }
        }

        TemplateExecutionService.replaceTemplateParams("template parameter ", original.getTemplateParams(),
                template.getTemplateParams(), given, added, replaced);

        replaceList("stdTemplateFields", "the standard fields", original.getStdTemplateFields(),
                template.getStdTemplateFields(), given, replaced);
        replaceList("editorFields", "the editor fields", original.getEditorFields(), template.getEditorFields(),
                given, replaced);
        replaceList("scriptResourcePaths", "the script paths", original.getScriptResourcePaths(),
                template.getScriptResourcePaths().stream().filter(p -> ScriptResource.path2type(p) != null).toList(),
                given, replaced);

        for (Domain domain : domainService.persistedDomains(template.getDomains())) {
            if (original.getDomains().stream().noneMatch(d -> Objects.equals(d.getDomainId(), domain.getDomainId()))) {
                original.getDomains().add(domain);
                added.add("domain " + domain.getDomainId());
            }
        }
        domainService.missingDomainIds(template.getDomains()).forEach(id -> kept.add("domain " + id + " not found"));

        List<String> notes = new ArrayList<>();
        if (activeChanged) {
            List<Category> tree = new ArrayList<>(descendants(original));
            for (Category a = original.getParent(); a != null; a = a.getParent()) {
                tree.add(a);
            }
            Map<Category, Boolean> before = new IdentityHashMap<>();
            tree.forEach(c -> before.put(c, c.isActive()));
            original.updateLineage();
            original.updateActiveFlag();
            List<String> changed = tree.stream().filter(c -> c.isActive() != before.get(c)).map(c -> entry(c)).toList();
            if (!changed.isEmpty()) {
                notes.add((original.isActive() ? "made active with it: " : "made inactive with it: ")
                        + String.join(", ", changed) + " (TreeBaseEntity.updateActiveFlag; not covered by the rule)");
            }
        }
        report.accept(new BootstrapRun.Additions(entry(original), added, replaced, kept, notes));
        if (added.isEmpty() && replaced.isEmpty()) {
            return original;
        }
        log.debug("Replaced in " + entry(original) + " (an earlier archive of this bootstrap run created it): added "
                + added + ", replaced " + replaced);
        return saveEntity(original);
    }

    /**
     * Replaces a list of values the file gives and that differs, an explicit empty list included, element by element
     * in the row's own collection, so that an equal list is never written
     */
    private static void replaceList(String field, String what, List<String> row, List<String> file, Given given,
                                    List<String> replaced) {
        List<String> to = file == null ? List.of() : file;
        if (!given.has(field) || sameList(row, to)) {
            return;
        }
        replaced.add(BootstrapRun.Additions.change(what, new ArrayList<>(row), new ArrayList<>(to)));
        for (int i = 0; i < to.size(); i++) {
            if (i < row.size()) {
                if (!Objects.equals(row.get(i), to.get(i))) {
                    row.set(i, to.get(i));
                }
            } else {
                row.add(to.get(i));
            }
        }
        while (row.size() > to.size()) {
            row.remove(row.size() - 1);
        }
    }

    /** A category's descendants, depth first */
    private static List<Category> descendants(Category category) {
        List<Category> all = new ArrayList<>();
        for (Category child : category.getChildren()) {
            all.add(child);
            all.addAll(descendants(child));
        }
        return all;
    }

    /** Whether two lists hold the same elements in the same order, whatever collection class holds them */
    private static boolean sameList(List<?> a, List<?> b) {
        return new ArrayList<>(a == null ? List.of() : a).equals(new ArrayList<>(b == null ? List.of() : b));
    }

    /**
     * The English name of the root category firing area messages are given. Load-bearing: the base product's
     * bootstrap carries a category of this name, and a deployment's own is found by it (an MRN would have to be
     * written into each running deployment's category).
     */
    public static final String FIRING_EXERCISES_CATEGORY_NAME = "Firing Exercises";

    /**
     * Returns the firing exercises category, the root category with the English name
     * {@value #FIRING_EXERCISES_CATEGORY_NAME}, or null if none is found.
     *
     * @return the firing exercises category or null if none is found
     */
    public Category getFiringExercisesCategory() {
        return findByName(FIRING_EXERCISES_CATEGORY_NAME, "en", null);
    }

    /**
     * Returns the last change date for categories or null if no category exists
     * @return the last change date for categories
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    @Override
    public Date getLastUpdated() {
        // max() answers one row, holding null when there is no category
        return em.createNamedQuery("Category.findLastUpdated", Date.class).getSingleResult();
    }

    /**
     * Called periodically every hour to re-sort the category tree
     *
     * Potentially, a heavy-duty function that scans the entire category tree,
     * sorts it and update the treeSortOrder. Use with care.
     *
     * @return if the sort order was updated
     */
    @Scheduled(cron="13 23 * * * ?")
    public void recomputeTreeSortOrder() {
        recomputeTreeSortOrder(CATEGORY_LAST_UPDATE);
    }

    /***************************************/
    /** AtonN functionality               **/
    /***************************************/

    /**
     * Resolves all categories that matches the given AtoNs
     * <p>
     * NB: This is a potentially expensive operation involving iteration of category lineages,
     *     including JavaScript evaluation of AtoN filters.
     *     However, given that there are only dozens - and not hundreds - of categories,
     *     we should be fine. Use with care though...
     *
     * @param categories the gross set of template categories to filter by AtoNs
     * @param atons the atons to find templates for
     * @param limit the max number of category templates to return
     * @return the matching template categories
     */
    private List<Category> resolveAtonCategories(List<Category> categories, List<AtonNodeVo> atons, int limit) {

        // Resolve matching categories via associated categories. Cache the result by category ID
        Map<Integer, Boolean> includeCategory = new HashMap<>();

        // Filter the templates
        return categories.stream()
                .filter(t -> matchesAtons(atons, t, includeCategory))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Checks if all AtoNs matches the AtoN filters of the template category lineage
     * @param atons the AtoNs to check
     * @param category the template category to test
     * @return if all AtoNs matches the AtoN filters of the template category lineage
     */
    private boolean matchesAtons(List<AtonNodeVo> atons, Category category, Map<Integer, Boolean> includeCategory) {

        // There must be at least one AtoN filter in the template category lineage to qualify
        boolean atonFiltered = false;

        // Check the category and all its parent categories
        for (Category cat = category; cat != null; cat = cat.getParent()) {
            if (includeCategory.containsKey(cat.getId())) {
                return includeCategory.get(cat.getId());
            }
            if (StringUtils.isNotBlank(cat.getAtonFilter())) {
                atonFiltered = true;
                boolean matchesAtons = matchesAtons(atons, cat.getAtonFilter());
                includeCategory.put(cat.getId(), matchesAtons);
                if (!matchesAtons) {
                    return false;
                }
            }
        }

        return atonFiltered;
    }

    /**
     * Checks if all AtoNs matches the AtoN filter
     * @param atons the AtoNs to check
     * @param atonFilter the AtoN filter to test
     * @return if all AtoNs matches the AtoN filter
     */
    private boolean matchesAtons(List<AtonNodeVo> atons, String atonFilter) {
        try {
            AtonFilter filter = AtonFilter.getInstance(atonFilter);
            return filter.matches(atons);
        } catch (ScriptException e) {
            return false;
        }
    }
}
