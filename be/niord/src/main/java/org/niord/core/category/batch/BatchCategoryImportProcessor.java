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
package org.niord.core.category.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryDesc;
import org.niord.core.category.CategoryService;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.domain.Domain;
import org.niord.model.DataFilter;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Filters categories that need to be a added or updated. Within the first bootstrap's rule, the values a later
 * archive gives replace those of a category an earlier archive of the run created, and what is new is added
 * ({@link BootstrapRun}, {@link CategoryService#replaceInCategory}). The job log says what was added and replaced,
 * once per category: a parent is imported again for each of its leaves. A domain the file names and this Niord does
 * not have is left out of the category, a parent included, and the job log names it, once per category.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchCategoryImportProcessor")
public class BatchCategoryImportProcessor extends AbstractItemHandler {

    @Inject
    CategoryService categoryService;

    @Inject
    BootstrapRun bootstrapRun;

    /** The categories whose outcome this job has logged: one processor per job */
    private final Set<String> logged = new HashSet<>();

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        SystemCategoryVo categoryVo = (SystemCategoryVo) item;

        DataFilter filter = DataFilter.get().fields("parent");
        Category category = new Category(categoryVo, filter);

        getLog().info("Creating or updating category " + category);
        // The file's domain ids of the category and of each of its parents, root first, read before the import
        // replaces each template's domains with the stored ones (the parents are imported through the leaf's chain)
        List<FileDomains> chain = new ArrayList<>();
        for (Category c = category; c != null; c = c.getParent()) {
            chain.addFirst(new FileDomains(c, domainIds(c)));
        }
        // The entry carries its whole chain of parents, so an entry without a parent is a root and matches a root only
        Category imported = categoryService.importCategory(category, true, true, BootstrapRun.scopeOf(bootstrapRun, job),
                additions -> {
                    if (logged.add(additions.entry() + additions.notes())) {
                        getLog().info(additions.line());
                    }
                }, true);
        for (FileDomains f : chain) {
            logLeftOutDomains(f.template(), f.template() == category ? imported : null, f.ids());
        }
        return imported;
    }

    /** A template category of the file and its domain ids as the file gives them */
    private record FileDomains(Category template, List<String> ids) {
    }

    /** The domain ids of a category, each once, in their order */
    private static List<String> domainIds(Category category) {
        return category.getDomains() == null ? List.of() : category.getDomains().stream()
                .filter(Objects::nonNull).map(Domain::getDomainId).filter(Objects::nonNull).distinct().toList();
    }

    /** The name of a template category: its English name, else its first, else null */
    private static String name(Category template) {
        CategoryDesc desc = template.getDesc("en") != null ? template.getDesc("en")
                : template.getDescs().isEmpty() ? null : template.getDescs().get(0);
        return desc == null ? null : desc.getName();
    }

    /**
     * Names in the job log the domains of a file's category, the leaf or one of its parents, that this Niord does not
     * have: the import leaves them out of the template (domain ids differ between deployments), and the category is
     * imported without them. A parent that the import did not reach (its child was found by its MRN) keeps
     * the file's domains and reports nothing. Only the template is read: the imported category is detached, its
     * collections not loaded, and only the leaf's id is known. Written once per category per job, since a parent is
     * imported again for each of its leaves; a category is told apart by the names of its path from the root.
     */
    private void logLeftOutDomains(Category template, Category imported, List<String> fileDomainIds) {
        Set<String> kept = new HashSet<>(domainIds(template));
        List<String> leftOut = fileDomainIds.stream().filter(id -> !kept.contains(id)).toList();
        if (leftOut.isEmpty()) {
            return;
        }
        List<String> path = new ArrayList<>();
        for (Category c = template; c != null; c = c.getParent()) {
            path.addFirst(String.valueOf(name(c)));
        }
        if (!logged.add("left out " + leftOut + " of " + path)) {
            return;
        }
        String name = name(template);
        getLog().warning("Category" + (imported != null ? " " + imported.getId() : "")
                + (name != null ? " '" + name + "'" : "")
                + ": left out " + (leftOut.size() == 1 ? "the domain " : "the domains ") + String.join(", ", leftOut)
                + ", which this Niord does not have");
    }
}
