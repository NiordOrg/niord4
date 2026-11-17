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

package org.niord.core.category.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.niord.core.bootstrap.Given;
import org.niord.core.category.CompositeParamType;
import org.niord.core.category.ListParamType;
import org.niord.core.category.ParamType;
import org.niord.core.category.StandardParamType;
import org.niord.model.IJsonSerializable;

/**
 * Abstract value object super class for the {@code ParamType} derived model entity
 */
@JsonTypeInfo(use= JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value=StandardParamTypeVo.class, name="STANDARD"),
        @JsonSubTypes.Type(value=ListParamTypeVo.class, name="LIST"),
        @JsonSubTypes.Type(value=CompositeParamTypeVo.class, name="COMPOSITE"),
})
public abstract class ParamTypeVo implements IJsonSerializable {

    Integer id;
    String name;

    /** Which fields the import file's item gives; only within the first bootstrap's rule ({@link Given}) */
    @JsonIgnore
    Given given;

    /**
     * Constructor
     */
    public ParamTypeVo() {
    }

    /**
     * Returns the entity associated with this value object
     */
    public ParamType toEntity() {
        ParamType paramType = null;
        if (this instanceof ListParamTypeVo) {
            paramType = new ListParamType((ListParamTypeVo)this);
        } else if (this instanceof CompositeParamTypeVo) {
            paramType = new CompositeParamType((CompositeParamTypeVo) this);
        } else if (this instanceof StandardParamTypeVo) {
            paramType = new StandardParamType((StandardParamTypeVo) this);
        }
        if (paramType != null) {
            paramType.setGiven(given);
        }
        return paramType;
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
