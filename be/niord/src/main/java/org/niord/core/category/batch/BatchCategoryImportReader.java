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

import tools.jackson.core.type.TypeReference;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.bootstrap.Given;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.util.JsonUtils;
import tools.jackson.databind.JsonNode;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads categories from a categories.json file.
 * <p>
 * Please note, the actual category-import.xml job file is not placed in the META-INF/batch-jobs of this project,
 * but rather, in the META-INF/batch-jobs folder of the niord-web project.<br>
 * This is because of a class-loading bug in the Wildfly implementation. See e.g.
 * https://issues.jboss.org/browse/WFLY-4988
 * <p>
 * Format of json file is defined by the CategoryVo class:
 * <pre>
 * [{
 *     "children": [
 *       {
 *         "descs": [ {
 *             "lang": "da",
 *              "name": "Kabelarbejde"
 *           },
 *           {
 *             "lang": "en",
 *             "name": "Cable operations"
 *           }
 *         ]
 *       },
 *       ...
 *     ],
 *     "descs": [
 *       {
 *         "lang": "da",
 *         "name": "Sejladshindring"
 *       },
 *       {
 *         "lang": "en",
 *         "name": "Obstruction"
 *       }
 *     ]
 *   },
 *   ...
 * ]
 * </pre>
 * <p>
 * Within the first bootstrap's rule, for a job of a later archive of the live bootstrap run, each category, parents
 * included, also carries which fields its item gives ({@link Given}), read from the file a second time as a tree.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchCategoryImportReader")
public class BatchCategoryImportReader extends AbstractItemHandler {

    List<SystemCategoryVo> categories;
    int categoriesNo = 0;

    @Inject
    BootstrapRun bootstrapRun;

    /** {@inheritDoc} **/
    @Override
    public void open(Serializable prevCheckpointInfo) throws Exception {

        // Get hold of the data file
        Path path = batchService.getBatchJobDataFile(jobContext.getInstanceId());

        // Load the charts from the file
        List<SystemCategoryVo> categoriesRoots = JsonUtils.readJson(
                new TypeReference<List<SystemCategoryVo>>(){},
                path);

        // Serialize the list of categories
        categories = new ArrayList<>();
        serializeCategories(null, categoriesRoots, categories);

        // Only for a later archive of the live bootstrap run: which fields each category's item gives
        if (BootstrapRun.scopeOf(bootstrapRun, job).archive() > 0) {
            setGiven(categoriesRoots, Given.readTree(path));
        }

        // Remove all non-leaf categories, since leaf categories will generate their parent categories as well
        categories.removeIf(c -> c.getChildren() != null && c.getChildren().size() > 0);

        if (prevCheckpointInfo != null) {
            categoriesNo = (Integer) prevCheckpointInfo;
        }

        getLog().info("Start processing " + categories.size() + " categories from index " + categoriesNo);
    }

    /** {@inheritDoc} **/
    @Override
    public Object readItem() throws Exception {
        if (categoriesNo < categories.size()) {
            getLog().info("Reading category no " + categoriesNo);
            return categories.get(categoriesNo++);
        }
        return null;
    }

    /** {@inheritDoc} **/
    @Override
    public Serializable checkpointInfo() throws Exception {
        return categoriesNo;
    }

    /** Sets each category's Given from its item, walking the tree as {@link #serializeCategories} does */
    private static void setGiven(List<SystemCategoryVo> siblings, JsonNode items) {
        if (siblings == null) {
            return;
        }
        for (int i = 0; i < siblings.size(); i++) {
            JsonNode item = items == null ? null : items.get(i);
            siblings.get(i).setGiven(Given.of(item));
            setGiven(siblings.get(i).getChildren(), item == null ? null : item.get("children"));
        }
    }

    /** Serialize the siblings root hierarchy to a flat list + define the parent field + resets ID fields */
    private void serializeCategories(SystemCategoryVo parent, List<SystemCategoryVo> siblings, List<SystemCategoryVo> categories) {
        if (siblings != null) {
            siblings.forEach(a -> {
                a.setId(null);
                a.setParent(parent);
                categories.add(a);
                serializeCategories(a, a.getChildren(), categories);
            });
        }
    }
}
