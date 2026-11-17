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

package org.niord.core.category.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.niord.core.bootstrap.Given;
import org.niord.core.category.CategoryType;
import org.niord.core.domain.vo.DomainVo;
import org.niord.model.message.CategoryVo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Extends the {@linkplain CategoryVo} model with system-specific fields and attributes.
 */
@SuppressWarnings("unused")
public class SystemCategoryVo extends CategoryVo implements Comparable<SystemCategoryVo> {

    CategoryType type = CategoryType.CATEGORY;
    List<SystemCategoryVo> children;
    Double siblingSortOrder;
    List<String> editorFields;
    String atonFilter;
    List<DomainVo> domains = new ArrayList<>();
    List<String> stdTemplateFields = new ArrayList<>();
    List<TemplateParamVo> templateParams = new ArrayList<>();
    List<String> scriptResourcePaths = new ArrayList<>();
    String messageId;

    /** Which fields the import file's item gives; only within the first bootstrap's rule ({@link Given}) */
    @JsonIgnore
    Given given;

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("all")
    public int compareTo(SystemCategoryVo category) {
        if (category == null) {
            return 0;
        }
        // siblingSortOrder is a boxed Double; compare numerically (treating null as 0.0) rather
        // than by reference (==), which fails for equal but distinct Doubles.
        double thisOrder = Objects.requireNonNullElse(siblingSortOrder, 0.0);
        double otherOrder = Objects.requireNonNullElse(category.getSiblingSortOrder(), 0.0);
        return Double.compare(thisOrder, otherOrder);
    }

    /** Returns the list of child categories, and creates an empty list if needed */
    public List<SystemCategoryVo> checkCreateChildren() {
        if (children == null) {
            children = new ArrayList<>();
        }
        return children;
    }

    /**
     * Recursively sorts the children
     */
    public void sortChildren() {
        if (children != null) {
            Collections.sort(children);
            children.forEach(SystemCategoryVo::sortChildren);
        }
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public CategoryType getType() {
        return type;
    }

    public void setType(CategoryType type) {
        this.type = type;
    }

    public List<SystemCategoryVo> getChildren() {
        return children;
    }

    public void setChildren(List<SystemCategoryVo> children) {
        this.children = children;
    }

    public Double getSiblingSortOrder() {
        return siblingSortOrder;
    }

    public void setSiblingSortOrder(Double siblingSortOrder) {
        this.siblingSortOrder = siblingSortOrder;
    }

    public List<String> getEditorFields() {
        return editorFields;
    }

    public void setEditorFields(List<String> editorFields) {
        this.editorFields = editorFields;
    }

    public String getAtonFilter() {
        return atonFilter;
    }

    public void setAtonFilter(String atonFilter) {
        this.atonFilter = atonFilter;
    }

    public List<DomainVo> getDomains() {
        return domains;
    }

    public void setDomains(List<DomainVo> domains) {
        this.domains = domains;
    }

    public List<String> getStdTemplateFields() {
        return stdTemplateFields;
    }

    public void setStdTemplateFields(List<String> stdTemplateFields) {
        this.stdTemplateFields = stdTemplateFields;
    }

    public List<TemplateParamVo> getTemplateParams() {
        return templateParams;
    }

    public void setTemplateParams(List<TemplateParamVo> templateParams) {
        this.templateParams = templateParams;
    }

    public List<String> getScriptResourcePaths() {
        return scriptResourcePaths;
    }

    public void setScriptResourcePaths(List<String> scriptResourcePaths) {
        this.scriptResourcePaths = scriptResourcePaths;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /** Returns which fields the import file's item gives, or null outside the first bootstrap's rule */
    @JsonIgnore
    public Given getGiven() {
        return given;
    }

    @JsonIgnore
    public void setGiven(Given given) {
        this.given = given;
    }
}
