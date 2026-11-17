/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.settings;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.niord.core.conf.TextResource;
import org.niord.core.conf.TextResourceProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Spring replacement for the CDI {@code @Produces @Setting} / {@code @TextResource}
 * qualifier-driven producers that lived on {@code SettingsService} / {@code TextResourceProducer}
 * under Quarkus.
 *
 * <p>It scans every bean for fields annotated with
 * {@link org.niord.core.settings.annotation.Setting} or {@link TextResource} and populates
 * them by reflection — {@code @Setting} via {@link SettingsService}, and {@code @TextResource} via
 * {@link TextResourceProducer#loadResourceText}. This reproduces the original injection semantics without CDI
 * {@code InjectionPoint} producers.
 *
 * <p>A bean created while the database cannot be read yet gets the setting's default
 * ({@link SettingsService#resolveDefault}: the settings file's value, read without the database), or the annotation
 * default when {@link SettingsService} itself is not available yet. Such a field is recorded and injected again
 * through the database once start-up has added the missing settings ({@link #reinjectDeferred}), so every bean ends
 * start-up with the database's value (docs/code-data-config-plan.md §5.2). A singleton's field still holds
 * that value until the next restart (SECOM-UI-1).
 */
@Component
public class SettingInjectionBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SettingInjectionBeanPostProcessor.class);
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    // ObjectProvider keeps the dependency lazy so this processor (and SettingsService) can be
    // created without an eager circular dependency.
    private final ObjectProvider<SettingsService> settingsService;

    /** A field injected without the database, to be injected again once start-up has added the missing settings */
    private record Deferred(Object bean, Field field, Setting template) {
    }

    private final List<Deferred> deferred = new ArrayList<>();

    /** Set by the first {@link #reinjectDeferred}: a field injected without the database later is not recorded */
    private volatile boolean started;

    public SettingInjectionBeanPostProcessor(ObjectProvider<SettingsService> settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Populate {@code @Setting} / {@code @TextResource} fields during the property-population
     * phase — i.e. <em>before</em> {@code @PostConstruct} init methods run (which is exactly
     * where {@code @Inject}/{@code @Autowired} injection happens too).
     */
    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        Class<?> type = bean.getClass();
        ReflectionUtils.doWithFields(type, field -> {
            org.niord.core.settings.annotation.Setting setting =
                    field.getAnnotation(org.niord.core.settings.annotation.Setting.class);
            if (setting != null) {
                injectSetting(bean, field, setting);
            }
            TextResource textResource = field.getAnnotation(TextResource.class);
            if (textResource != null) {
                injectTextResource(bean, field, textResource, type);
            }
        });
        return pvs;
    }

    // ------------------------------------------------------------------------------------------------

    private void injectSetting(Object bean, Field field,
                               org.niord.core.settings.annotation.Setting ann) {
        Setting template = annotationToSetting(ann, field.getName());
        if (template.getType() == Setting.Type.Password) {
            // Before the value is resolved: no log line may show it, whatever a niord.json says
            SettingsService.registerDeclaredPasswordKey(template.getKey());
        }
        SettingsService svc = available();
        Object value;
        try {
            if (svc == null) {
                throw new IllegalStateException("SettingsService is not available yet");
            }
            value = resolveSettingValue(svc, template, field.getType());
        } catch (Exception e) {
            // e.g. EntityManager not ready for a very early bean: the default now, the database's value after start-up
            log.debug("@Setting '{}' of {} resolved without the database during startup: {}", template.getKey(),
                    bean.getClass().getSimpleName(), e.toString());
            value = defaultWithoutDatabase(svc, template);
            synchronized (deferred) {
                if (!started) {
                    deferred.add(new Deferred(bean, field, template));
                } else {
                    // Nothing injects again after start-up, and a record would keep the bean reachable
                    log.debug("@Setting '{}' of {}.{} keeps the default resolved without the database",
                            template.getKey(), bean.getClass().getSimpleName(), field.getName());
                }
            }
        }
        if (value == null) {
            // fall back to the parsed annotation default (mirrors the CDI producer when DB has no override)
            value = template.getValue();
        }
        // The typed getters can hand back a raw String (e.g. an un-coerced default); coerce
        // to the field's declared type so e.g. a Path field is never assigned a String.
        value = coerce(value, field.getType());
        setField(bean, field, value);
    }

    /**
     * Injects again, through the database, each field that was injected without it while the context started, and
     * forgets them. Called by {@link SettingsService} once start-up has added the missing settings; a field injected
     * without the database after the first call keeps that default and is not recorded.
     *
     * @param svc the settings service
     */
    void reinjectDeferred(SettingsService svc) {
        List<Deferred> pending;
        synchronized (deferred) {
            started = true;
            pending = new ArrayList<>(deferred);
            deferred.clear();
        }
        for (Deferred d : pending) {
            try {
                Object value = resolveSettingValue(svc, d.template(), d.field().getType());
                if (value == null) {
                    value = d.template().getValue();
                }
                value = coerce(value, d.field().getType());
                ReflectionUtils.makeAccessible(d.field());
                if (value != null && !Objects.equals(ReflectionUtils.getField(d.field(), d.bean()), value)) {
                    setField(d.bean(), d.field(), value);
                    log.info("@Setting '{}' of {}.{} injected again: the database's value differs from the one "
                                    + "injected while the database could not be read",
                            d.template().getKey(), d.bean().getClass().getSimpleName(), d.field().getName());
                }
            } catch (Exception e) {
                log.warn("@Setting '{}' of {}.{} keeps the value injected without the database: {}",
                        d.template().getKey(), d.bean().getClass().getSimpleName(), d.field().getName(), e.toString());
            }
        }
    }

    /** The settings service, or null while it cannot be created yet */
    private SettingsService available() {
        try {
            return settingsService.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The setting's default without the database: {@link SettingsService#resolveDefault}, called on the service
     * itself rather than its transactional proxy, which would need a transaction; null when there is no service yet
     */
    private static Object defaultWithoutDatabase(SettingsService svc, Setting template) {
        if (svc == null) {
            return null;
        }
        try {
            Object target = AopProxyUtils.getSingletonTarget(svc);
            return (target instanceof SettingsService s ? s : svc).resolveDefault(template);
        } catch (Exception e) {
            log.debug("@Setting '{}' has no default without the database: {}", template.getKey(), e.toString());
            return null;
        }
    }

    /** Resolves the setting through the settings service with the field type's getter */
    private static Object resolveSettingValue(SettingsService svc, Setting template, Class<?> ft) {
        if (ft == String.class)                       return svc.getString(template);
        if (ft == Integer.class || ft == int.class)   return svc.getInteger(template);
        if (ft == Boolean.class || ft == boolean.class) return svc.getBoolean(template);
        if (ft == Long.class || ft == long.class)     return svc.getLong(template);
        if (ft == Float.class || ft == float.class)   return svc.getFloat(template);
        if (ft == Double.class || ft == double.class) return svc.getDouble(template);
        if (Path.class.isAssignableFrom(ft))          return svc.getPath(template);
        if (Date.class.isAssignableFrom(ft))          return svc.getDate(template);
        return svc.get(template);
    }

    /**
     * Port of {@code SettingsService.ip2setting(InjectionPoint)} reading from the annotation. The settings catalog
     * generator reads each {@code @Setting} field through it, so the catalog's code default is the injected one.
     */
    static Setting annotationToSetting(org.niord.core.settings.annotation.Setting ann, String fieldName) {
        String key = (ann.value() == null || ann.value().isBlank()) ? fieldName : ann.value();
        String defVal = ann.defaultValue();
        Object value = null;
        switch (ann.type()) {
            case String:
            case Password: value = defVal; break;
            case Integer:  value = defVal.isEmpty() ? null : Integer.valueOf(defVal); break;
            case Long:     value = defVal.isEmpty() ? null : Long.valueOf(defVal); break;
            case Float:    value = defVal.isEmpty() ? null : Float.valueOf(defVal); break;
            case Double:   value = defVal.isEmpty() ? null : Double.valueOf(defVal); break;
            case Boolean:  value = defVal.isEmpty() ? null : Boolean.valueOf(defVal); break;
            case Date:     value = defVal.isEmpty() ? null : Long.valueOf(defVal); break;
            case Path:     value = defVal.isEmpty() ? null : Paths.get(defVal); break;
            case json:
                try { value = defVal.isEmpty() ? null : MAPPER.readValue(defVal, Object.class); }
                catch (Exception ignored) { }
                break;
        }
        return new Setting(key, value, ann.type(), ann.description(), ann.cached(), ann.web(), ann.editable())
                .scope(ann.scope())
                .required(ann.required());
    }

    /** Coerce a resolved setting value to the declared field type (String -> Path/Integer/Boolean/...). */
    private static Object coerce(Object value, Class<?> ft) {
        if (value == null || ft.isInstance(value)) {
            return value;
        }
        String s = value.toString();
        try {
            if (ft == String.class)                       return s;
            if (ft == Integer.class || ft == int.class)   return Integer.valueOf(s);
            if (ft == Long.class || ft == long.class)     return Long.valueOf(s);
            if (ft == Boolean.class || ft == boolean.class) return Boolean.valueOf(s);
            if (ft == Float.class || ft == float.class)   return Float.valueOf(s);
            if (ft == Double.class || ft == double.class) return Double.valueOf(s);
            if (Path.class.isAssignableFrom(ft))          return Paths.get(s);
        } catch (RuntimeException e) {
            log.debug("Could not coerce setting value '{}' to {}: {}", s, ft, e.toString());
        }
        return value;
    }

    private void injectTextResource(Object bean, Field field, TextResource ann, Class<?> declaring) {
        String text = TextResourceProducer.loadResourceText(declaring, ann.value());
        setField(bean, field, text);
    }

    private void setField(Object bean, Field field, Object value) {
        if (value == null) {
            return;
        }
        ReflectionUtils.makeAccessible(field);
        try {
            ReflectionUtils.setField(field, bean, value);
        } catch (Exception e) {
            log.warn("Failed injecting value into {}.{}: {}", bean.getClass().getSimpleName(), field.getName(), e.toString());
        }
    }
}
