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

package org.niord.core.category;

import org.niord.core.bootstrap.Given;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.core.model.BaseEntity;
import org.niord.model.DataFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Transient;

/**
 * Base class for the template list parameter type and composite parameter type
 */
@Entity
@NamedQueries({
        @NamedQuery(name  = "ParamType.findAll",
                query = "select t from ParamType t order by t.name asc"),
        @NamedQuery(name  = "ParamType.findByName",
                query = "select t from ParamType t where t.name = :name"),
        @NamedQuery(name  = "ParamType.findByNameIgnoreCase",
                query = "select t from ParamType t where lower(t.name) = lower(:name)")
})
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public abstract class ParamType extends BaseEntity<Integer> {

    @Column(unique=true)
    protected String name;

    /** Which fields the import file's item gave; never stored ({@link Given}) */
    @Transient
    Given given;

    /** Constructor **/
    public ParamType() {
    }

    /** Constructor **/
    public ParamType(ParamTypeVo type) {
        this.setId(type.getId());
        this.name = type.getName();
    }

    /** Returns a value object representation of this entity **/
    public abstract ParamTypeVo toVo(DataFilter filter);

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Returns which fields the import file's item gives, or null outside the first bootstrap's rule */
    public Given getGiven() {
        return given;
    }

    public void setGiven(Given given) {
        this.given = given;
    }
}
