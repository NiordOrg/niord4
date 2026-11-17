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
package org.niord.web;

import org.niord.core.NiordApp;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.category.Category;
import org.niord.core.category.CategorySearchParams;
import org.niord.core.category.CategoryService;
import org.niord.core.category.CategoryType;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.user.Roles;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
import org.niord.model.message.CategoryDescVo;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.apache.commons.lang3.StringUtils;

/**
 * REST interface for accessing categories.
 */
@RestController
@RequestMapping("/rest/categories")
@Transactional
@PermitAll
public class CategoryRestService extends AbstractBatchableRestService {

    /** The most AtoNs a categories search accepts; each is run through the AtoN filter of every candidate */
    static final int MAX_SEARCH_ATONS = 100;

    private final NiordApp app;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CategoryRestService.class);

    private final CategoryService categoryService;

    CategoryRestService(NiordApp app, CategoryService categoryService) {
        this.app = app;
        this.categoryService = categoryService;
    }

    /**
     * Searches for categories matching the given name in the given language
     *
     * @param lang  the language
     * @param name  the search name
     * @param domain  if defined, restricts the search to the categories of the given domain
     * @param limit the maximum number of results
     * @return the search result
     */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<SystemCategoryVo> searchCategories(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "type", required = false) CategoryType type,
            @RequestParam(name = "ancestorId", required = false) Integer ancestorId,
            @RequestParam(name = "domain", defaultValue = "false") String domain,
            @RequestParam(name = "inactive", defaultValue = "false") boolean inactive,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {

        return search(lang, name, type, ancestorId, domain, inactive, limit, null);
    }

    /**
     * Searches for categories matching the given name in the given language.
     *
     * This version of the search function extends the functionality with support
     * for filtering by AtoNs. Every AtoN is run through the AtoN filter of every candidate category, so
     * the search is for editors only and takes at most {@link #MAX_SEARCH_ATONS} AtoNs.
     *
     * @param lang  the language
     * @param name  the search name
     * @param domain  if defined, restricts the search to the categories of the given domain
     * @param limit the maximum number of results
     * @return the search result
     */
    @PutMapping(value = "/search",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<SystemCategoryVo> searchCategories(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "type", required = false) CategoryType type,
            @RequestParam(name = "ancestorId", required = false) Integer ancestorId,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "inactive", defaultValue = "false") boolean inactive,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestBody List<AtonNodeVo> atons) {

        if (atons != null && atons.size() > MAX_SEARCH_ATONS) {
            throw new NiordWebException("At most " + MAX_SEARCH_ATONS + " AtoNs per search", 400);
        }
        return search(lang, name, type, ancestorId, domain, inactive, limit, atons);
    }

    /** The search behind both handlers; the anonymous GET passes no AtoNs */
    private List<SystemCategoryVo> search(String lang, String name, CategoryType type, Integer ancestorId,
                                          String domain, boolean inactive, int limit, List<AtonNodeVo> atons) {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.PARENT)
                .lang(lang);

        CategorySearchParams params = new CategorySearchParams();
        //params.sortBy(CategorySearchParams.TREE_SORT_ORDER); // Default order

        params.language(lang)
                .type(type)
                .name(name)
                .ancestorId(ancestorId)
                .domain(domain)
                .inactive(inactive)
                .atons(atons)
                .maxSize(limit);

        log.info(String.format("Searching for categories %s", params));

        List<SystemCategoryVo> result = categoryService.searchCategories(params).stream()
                .map(c -> c.toVo(SystemCategoryVo.class, filter))
                .collect(Collectors.toList());

//        // A bit of a hack to support alphabetic sort
//
//        String l = lang == null ? "en" : lang;
//        Comparator<SystemCategoryVo> cmp = new Comparator<>() {
//
//            @Override
//            public int compare(SystemCategoryVo o1, SystemCategoryVo o2) {
//                CategoryDescVo c1 = o1.getDesc(l);
//                CategoryDescVo c2 = o2.getDesc(l);
//                if (Objects.equals(c1, c2)) {
//                    return 0;
//                }
//                if (c1 == null) {
//                    return -1;
//                } else if (c2 == null) {
//                    return 1;
//                }
//                return c1.getName().compareToIgnoreCase(c2.getName());
//            }};
//        Collections.sort(result, cmp);

        return result;
    }

    /** Returns the categories with the given IDs */
    @GetMapping(value = "/search/{categoryIds}", produces = "application/json;charset=UTF-8")
    public List<SystemCategoryVo> searchCategoryIds(@PathVariable("categoryIds") String categoryIds,
                                      @RequestParam(name = "lang", defaultValue = "en") String lang,
                                      @RequestParam(name = "limit", defaultValue = "1000") int limit) {

        log.debug(String.format("Searching for categories ids=%s, lang=%s, limit=%d", categoryIds, lang, limit));

        List<Integer> ids = Arrays.stream(categoryIds.split(","))
                .map(Integer::valueOf)
                .collect(Collectors.toList());

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .lang(lang)
                .fields(DataFilter.PARENT);

        return categoryService.getCategoryDetails(ids).stream()
                .map(c -> c.toVo(SystemCategoryVo.class, filter))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Returns all categories via a list of hierarchical root categories */
    @GetMapping(value = "/category-roots", produces = "application/json;charset=UTF-8")
    public List<SystemCategoryVo> getCategoryRoots(@RequestParam(name = "lang", required = false) String lang) {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .lang(lang)
                .fields(DataFilter.CHILDREN);

        return categoryService.getCategoryTree().stream()
                .map(c -> c.toVo(SystemCategoryVo.class, filter))
                .collect(Collectors.toList());
    }

    /** Returns all categories via a list of hierarchical root categories */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<SystemCategoryVo> getAll() {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.CHILDREN);

        return categoryService.getCategoryTree().stream()
                .map(c -> c.toVo(SystemCategoryVo.class, filter))
                .collect(Collectors.toList());
    }

    /** Returns all categories via a list of hierarchical root categories. Prunes the data for export purposes */
    @GetMapping(value = "/export", produces = "application/json;charset=UTF-8")
    public List<SystemCategoryVo> exportCategories() {
        return getAll().stream()
                .map(this::exportVersion)
                .collect(Collectors.toList());
    }

    /** Removes various data from the category to make it usable for export **/
    private SystemCategoryVo exportVersion(SystemCategoryVo category) {
        category.setId(null);
        if (category.getDomains() != null) {
            category.setDomains(category.getDomains().stream()
                .map(d -> new DomainVo(d.getDomainId(), d.isActive()))
                .collect(Collectors.toList()));
        }
        // Update child categories recursively
        if (category.getChildren() != null) {
            category.getChildren().forEach(this::exportVersion);
        }
        return category;
    }

    /** Returns the category with the given ID */
    @GetMapping(value = "/category/{categoryId}", produces = "application/json;charset=UTF-8")
    public SystemCategoryVo getCategory(
            @PathVariable("categoryId") Integer categoryId,
            @RequestParam(name = "parent", defaultValue = "false") boolean includeParents
    ) throws Exception {
        log.debug("Getting category " + categoryId);
        Category category = categoryService.getCategoryDetails(categoryId);

        // Return the category without child categories; optionally include the parent (CR1),
        // mirroring AreaRestService.getArea. PARENT only (not CHILDREN) to avoid the CAT1 cycle.
        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage());
        if (includeParents) {
            filter = filter.fields(DataFilter.PARENT);
        }

        return category == null ? null : category.toVo(SystemCategoryVo.class, filter);
    }

    /** Creates a new category */
    @PostMapping(value = {"/category", "/category/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public SystemCategoryVo createCategory(@RequestBody SystemCategoryVo categoryVo) throws Exception {
        // A create body must not carry an id: Category(categoryVo) copies it, and saveEntity() then MERGES
        // onto that existing row rather than persisting a new one, so the "create" would silently overwrite an
        // unrelated category. The service-level guard used by e.g. SourceService.createSource cannot be applied
        // here, because CategoryService.createCategory is shared with importCategory(), which legitimately
        // receives categories carrying a foreign system's id.
        if (categoryVo.getId() != null) {
            throw new NiordWebException(400);
        }
        // Here, not in CategoryService.createCategory, which the imports share and whose behaviour stays as it is
        if (categoryVo.getDescs() == null
                || categoryVo.getDescs().stream().noneMatch(d -> d != null && StringUtils.isNotBlank(d.getName()))) {
            throw new NiordWebException("A category needs a name in at least one language", 400);
        }
        Category category = new Category(categoryVo);
        log.info("Creating category " + category);
        Integer parentId = (categoryVo.getParent() == null) ? null : categoryVo.getParent().getId();
        return categoryService.createCategory(category, parentId).toVo(SystemCategoryVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Updates an category */
    @PutMapping(value = "/category/{categoryId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public SystemCategoryVo updateCategory(@PathVariable("categoryId") Integer categoryId, @RequestBody SystemCategoryVo categoryVo) throws Exception {
        if (!Objects.equals(categoryId, categoryVo.getId())) {
            throw new NiordWebException(400);
        }
        // A body without a sibling order keeps the stored one: the editor sends none, and new Category(vo) would turn
        // the missing value into 0, moving the category first among its siblings. Here, not in
        // CategoryService.updateCategoryData, which the imports share and whose behaviour stays as it is
        if (categoryVo.getSiblingSortOrder() == null) {
            Category stored = categoryService.getCategoryDetails(categoryId);
            if (stored != null) {
                categoryVo.setSiblingSortOrder(stored.getSiblingSortOrder());
            }
        }
        Category category = new Category(categoryVo);
        log.info("Updating category " + category);
        return categoryService.updateCategoryData(category).toVo(SystemCategoryVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Deletes the given category */
    @DeleteMapping("/category/{categoryId}")
    @RolesAllowed(Roles.SYSADMIN)
    public boolean deleteCategory(@PathVariable("categoryId") Integer categoryId) throws Exception {
        log.info("Deleting category " + categoryId);
        return categoryService.deleteCategory(categoryId);
    }

    /** Move an category to a new parent category **/
    @PutMapping(value = "/move-category", consumes = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public boolean moveCategory(@RequestBody MoveCategoryVo moveCategoryVo) throws Exception {
        log.info("Moving category " + moveCategoryVo.getCategoryId() + " to " + moveCategoryVo.getParentId());
        return categoryService.moveCategory(moveCategoryVo.getCategoryId(), moveCategoryVo.getParentId());
    }

    /** Changes sort order of an category in the category tree by moving it up or down among siblings */
    @PutMapping(value = "/change-sort-order", consumes = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public boolean changeSortOrder(@RequestBody ChangeSortOrderVo changeSortOrderVo) throws Exception {
        log.info("Changing sort-order of category " + changeSortOrderVo.getCategoryId()
                + " moving " + (changeSortOrderVo.isMoveUp() ? "up" : "down"));
        return categoryService.changeSortOrder(changeSortOrderVo.getCategoryId(), changeSortOrderVo.isMoveUp());
    }

    /** Re-computes the sort order of the category tree */
    @PutMapping("/recompute-tree-sort-order")
    @RolesAllowed(Roles.SYSADMIN)
    public boolean recomputeTreeSortOrder() {
        categoryService.recomputeTreeSortOrder();
        return true;
    }

    /**
     * Imports an uploaded Categories json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-categories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importCategories(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "category-import");
    }

    /**
     * ******************
     * Helper classes
     * *******************
     */

    /** Encapsulates the parameters used for moving an category to a new parent category */
    @SuppressWarnings("unused")
    public static class MoveCategoryVo implements IJsonSerializable {
        Integer categoryId, parentId;

        public Integer getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Integer categoryId) {
            this.categoryId = categoryId;
        }

        public Integer getParentId() {
            return parentId;
        }

        public void setParentId(Integer parentId) {
            this.parentId = parentId;
        }
    }

    /** Encapsulates the parameters used for moving a category up or down among sibling categories */
    @SuppressWarnings("unused")
    public static class ChangeSortOrderVo implements IJsonSerializable {
        Integer categoryId;
        boolean moveUp;

        public Integer getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Integer categoryId) {
            this.categoryId = categoryId;
        }

        public boolean isMoveUp() {
            return moveUp;
        }

        public void setMoveUp(boolean moveUp) {
            this.moveUp = moveUp;
        }
    }
}
