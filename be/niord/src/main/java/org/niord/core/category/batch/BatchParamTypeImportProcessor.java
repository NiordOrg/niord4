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
package org.niord.core.category.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.category.ParamType;
import org.niord.core.category.TemplateExecutionService;
import org.niord.core.category.vo.ParamTypeVo;

import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Processes parameter types. Within the first bootstrap's rule, the values a later archive gives replace those of a
 * type an earlier archive of the run created, and what is new is added ({@link BootstrapRun},
 * {@link TemplateExecutionService#replaceInParamType}); a type whose kind differs between the archives fails the job.
 * The job log says what was added and replaced.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchParamTypeImportProcessor")
public class BatchParamTypeImportProcessor extends AbstractItemHandler {

    @Inject
    TemplateExecutionService templateExecutionService;

    @Inject
    BootstrapRun bootstrapRun;

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        ParamTypeVo paramTypeVo = (ParamTypeVo)item;

        ParamType paramType = paramTypeVo.toEntity();

        // The standard types are code, held in memory (TemplateExecutionService): an item with a standard type's
        // name is skipped, as its update would otherwise create a second row. The rest of the file imports.
        if (TemplateExecutionService.isStandardParamType(paramType.getName())) {
            getLog().info("Skipping parameter type " + paramType.getName() + ": it is a standard type");
            return null;
        }

        getLog().info("Creating or updating parameter types " + paramType.getName());

        // The type the item updates is found by its exact name, on purpose. A look-up that ignored case would let an
        // item named "lights" find the type "Lights" and rename it, and the template parameters that name their type
        // "Lights" (exactly, with no foreign key) would lose it. An item whose name differs from an existing type's
        // only in case is a new type, which createParamType refuses, as names are unique without regard to case.
        ParamType original = templateExecutionService.getParamTypeByName(paramType.getName());
        BootstrapRun.Scope scope = BootstrapRun.scopeOf(bootstrapRun, job);

        if (original == null) {
            ParamType created = templateExecutionService.createParamType(paramType);
            scope.created(BootstrapRun.Kind.PARAM_TYPE, created.getName());
            return created;
        } else if (scope.createdEarlier(BootstrapRun.Kind.PARAM_TYPE, original.getName())) {
            TemplateExecutionService.checkSameKind(original, paramType,
                    scope.createdBy(BootstrapRun.Kind.PARAM_TYPE, original.getName()), scope.archive());
            getLog().info(templateExecutionService.replaceInParamType(original, paramType, paramType.getGiven())
                    .line());
            return original;
        } else {
            paramType.setId(original.getId());
            return templateExecutionService.updateParamType(paramType);
        }
    }
}
