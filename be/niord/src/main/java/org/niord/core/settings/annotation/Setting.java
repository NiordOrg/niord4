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
package org.niord.core.settings.annotation;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/**
 * Annotation used to inject settings
 */
// PORT: §13 dropped CDI @Nonbinding members; the qualifier-driven @Setting producer
// PORT: is deferred — re-wire @Setting injection during the IT stage.
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface Setting {

    String value() default "";
    String defaultValue() default "";
    org.niord.core.settings.Setting.Type type() default org.niord.core.settings.Setting.Type.String;
    String description() default "";
    boolean cached() default true;
    boolean editable() default true;
    boolean web() default false;

    /** Operator: a value an operator sets on the Settings page; state: a value the system writes */
    org.niord.core.settings.Setting.Scope scope() default org.niord.core.settings.Setting.Scope.operator;

    /** No settings file may leave the setting to the base product default */
    boolean required() default false;
}
