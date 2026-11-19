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

package org.niord.core.promulgation;

import org.springframework.stereotype.Component;

import org.niord.core.NiordApp;
import org.niord.core.category.ScriptExecutionService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.Message;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationServiceVo;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages the list of PromulgationServices, such as MailingListPromulgationService, NavtexPromulgationService, etc.
 * <p>
 * The PromulgationManager facilitates a plug-in architecture where all promulgation services must register
 * in an init method, handing the @Observes StartupEvent events.
 * <p>
 * Each promulgation service is associated with a list of {@linkplain PromulgationType} entities.
 * As an example, there may be "NAVTEX-DK" and "NAVTEX-GL" types managed by NAVTEX promulgation service.
 * <p>
 * In turn, messages are associated with a list of {@linkplain BaseMessagePromulgation}-derived entities that
 * are each tied to a promulgation type.
 */
@Component
@SuppressWarnings("unused")
public class PromulgationManager {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PromulgationManager.class);

    @Inject
    DomainService domainService;

    @Inject
    PromulgationTypeService promulgationTypeService;

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    NiordApp app;

    // PORT: was @Inject @All List<InstanceHandle<BasePromulgationService>> (Quarkus Arc
    // multi-bean injection with bean handles). Spring injects all beans of a type directly
    // as a List, so the InstanceHandle wrapper is dropped.
    @Inject
    List<BasePromulgationService> services;

    Map<String, Class<? extends BasePromulgationService>> serviceRegistry = new ConcurrentHashMap<>();

    /***************************************/
    /** Promulgation Service Handling     **/
    /***************************************/

    /**
     * Registers the promulgation service
     * @param service the promulgation service to register
     */
    public void registerPromulgationService(BasePromulgationService service) {
        serviceRegistry.put(service.getServiceId(), service.getClass());
        log.info("Registered promulgation service " + service.getServiceId());
    }

    // PORT: the Quarkus services self-registered via @Observes StartupEvent. The Spring
    // @EventListener(ApplicationReadyEvent) equivalent does not fire reliably for every inherited
    // bean method under @SpringBootTest, so populate the registry eagerly from the injected list of
    // all BasePromulgationService beans (registerPromulgationService is idempotent).
    @jakarta.annotation.PostConstruct
    void initServiceRegistry() {
        if (services != null) {
            services.forEach(this::registerPromulgationService);
        }
    }

    /**
     * Returns the registered set of promulgation services
     * @return the registered set of promulgation services
     */
    public List<PromulgationServiceVo> promulgationServices() {
        return serviceRegistry.entrySet().stream()
                .map(s -> {
                    BasePromulgationService service = instantiatePromulgationService(s.getValue());
                    String serviceName = service == null ? s.getKey() : service.getServiceName();
                    return new PromulgationServiceVo(s.getKey(), serviceName);
                })
                .collect(Collectors.toList());
    }

    /***************************************/
    /** Message Life-cycle Management     **/
    /***************************************/

    /**
     * Updates and adds promulgations to the message value object.
     * If the "strict" parameter is true, all promulgation types not currently active will be removed.
     *
     * @param message the message value object
     * @param strict if strict is true, all promulgation types not currently active will be removed
     */
    public void onLoadSystemMessage(SystemMessageVo message, boolean strict) throws PromulgationException {

        // The set of promulgation types to consider is the the currently active promulgation types for
        // the current domain and message type. If strict is not true, we add all promulgation types
        // already defined for the message.
        Map<String, PromulgationType> types = new HashMap<>();
        promulgationTypeService.getActivePromulgationTypes(message.getType()).forEach(pt -> types.put(pt.getTypeId(), pt));

        if (!strict) {
            promulgationTypeService.getPromulgationTypes(message).forEach(pt -> types.put(pt.getTypeId(), pt));
        } else if (message.getPromulgations() != null) {
            message.getPromulgations().removeIf(p -> !types.containsKey(p.getType().getTypeId()));
        }

        // Let the associated services process the message
        for (PromulgationType type : types.values()) {
            BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
            if (service != null) {
                service.onLoadSystemMessage(message, type);
            }
        }

        // Sort the promulgations according to priority
        if (message.getPromulgations() != null) {
            message.getPromulgations()
                    .sort(Comparator.comparingInt(p -> p.getType().getPriority()));
        }
    }

    /**
     * When a message is created as a copy of another message, let the registered promulgation services
     * check up on promulgations.
     *
     * @param message the message value object
     */
    public void onCopyMessage(SystemMessageVo message) throws PromulgationException {
        if (message.getPromulgations() != null) {
            // Always reset the ID of the copied promulgation
            message.getPromulgations().forEach(p -> p.setId(null));

            for (PromulgationType type : promulgationTypeService.getPromulgationTypes(message)) {
                BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
                if (service != null) {
                    service.onCopyMessage(message, type);
                } else {
                    log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
                }
            }
        }
    }

    /**
     * Updates the promulgation status depending on the promulgation type requirements.
     *
     * @param message the message value object
     */
    public void checkDefaultPromulgationRequirements(SystemMessageVo message) throws PromulgationException {
        promulgationTypeService.getPromulgationTypes(message)
                .forEach(type -> {
                    BaseMessagePromulgationVo p = message.promulgation(type.getTypeId());
                    p.setPromulgate(
                            type.getRequirement() == Requirement.DEFAULT ||
                            type.getRequirement() == Requirement.MANDATORY);
                });
    }

    /**
     * Prior to creating a new message, let the registered promulgation services check up on promulgations.
     * @param message the message about to be created
     */
    public void onCreateMessage(Message message) throws PromulgationException {
        for (PromulgationType type : persistedPromulgationTypes(message)) {
            BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
            if (service != null) {
                service.onCreateMessage(message, type);
            } else {
                log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
            }
        }
    }

    /**
     * Prior to updating an existing message, let the registered promulgation services check up on promulgations.
     * @param message the message about to be updated
     */
    public void onUpdateMessage(Message message) throws PromulgationException {
        for (PromulgationType type : persistedPromulgationTypes(message)) {
            BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
            if (service != null) {
                service.onUpdateMessage(message, type);
            } else {
                log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
            }
        }
    }

    /**
     * Prior to changing status of an existing message, let the registered promulgation services check up on promulgations.
     * @param message the message about to be updated
     */
    public void onUpdateMessageStatus(Message message) throws PromulgationException {
        for (PromulgationType type : persistedPromulgationTypes(message)) {
            BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
            if (service != null) {
                service.onUpdateMessageStatus(message, type);
            } else {
                log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
            }
        }
    }

    /***************************************/
    /** Generating promulgations          **/
    /***************************************/

    /**
     * Manually generates a message promulgation record for the given type and message based on the contents of the message.
     * <p>
     * A type whose service is not registered (a type row that outlived its service) generates nothing: the message is
     * left as it is, with a warning, as the life-cycle functions above skip such a type.
     * @param typeId the type of promulgation to generate
     * @param message the message template to generate a promulgation for
     * @return the promulgation, or for a type whose service is not registered the message's promulgation of the type
     *         as it was (null for none)
     */
    public BaseMessagePromulgationVo generateMessagePromulgation(String typeId, SystemMessageVo message) throws PromulgationException {

        // Generate the message promulgation
        PromulgationType type = persistedPromulgationType(typeId);
        BasePromulgationService promulgationService = instantiatePromulgationService(type.getServiceId());
        if (promulgationService == null) {
            log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
            return message.getPromulgations() == null ? null : message.getPromulgations().stream()
                    .filter(mp -> mp.getType() != null && typeId.equals(mp.getType().getTypeId()))
                    .findFirst()
                    .orElse(null);
        }

        // Create a new template promulgation of the given type and add it to the message promulgation list
        BaseMessagePromulgationVo p = promulgationService.generateMessagePromulgation(message, type);
        message.checkCreatePromulgations().removeIf(mp -> mp.getType().getTypeId().equals(typeId));
        message.getPromulgations().add(p);
        message.getPromulgations().sort(Comparator.comparingInt(mp -> mp.getType().getPriority()));

        // Check if there is any associated script resources to execute
        if (!type.getScriptResourcePaths().isEmpty()) {
            Map<String, Object> contextData = new HashMap<>();
            contextData.put("languages", app.getLanguages());
            contextData.put("message", message);
            contextData.put("promulgation", p);
            contextData.put("promulgationType", p.getType());
            contextData.put("params", new HashMap<>());
            try {
                scriptExecutionService.executeScriptResources(type.getScriptResourcePaths(), contextData);
            } catch (Exception e) {
                // The script adjusts the broadcast text (a header, a footer, the wording): without it the text is
                // not the one this deployment sends, so Generate fails for the type instead of answering it
                log.error("Failed executing script resources associated with promulgation type " + typeId, e);
                throw new PromulgationException("The script of promulgation type " + typeId + " failed: "
                        + e.getMessage(), e);
            }
        }

        // Allow the service to clean up
        promulgationService.messagePromulgationGenerated(message, type);

        return p;
    }

    /**
     * Called when message promulgations have been generated, either manually by calling {@code generateMessagePromulgation}
     * or by executing a template.
     * @param message the message template to generate a promulgation for
     */
    public void messagePromulgationGenerated(SystemMessageVo message) throws PromulgationException {
        if (message.getPromulgations() != null) {
            for (BaseMessagePromulgationVo promulgation : message.getPromulgations()) {
                PromulgationType type = persistedPromulgationType(promulgation.getType().getTypeId());
                BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
                if (service != null) {
                    service.messagePromulgationGenerated(message, type);
                } else {
                    log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
                }
            }
        }
    }

    /**
     * Resets the message promulgations
     * @param message the message template to reset promulgation for
     */
    public void resetMessagePromulgations(SystemMessageVo message) throws PromulgationException {
        if (message.getPromulgations() != null) {
            for (BaseMessagePromulgationVo promulgation : message.getPromulgations()) {
                PromulgationType type = persistedPromulgationType(promulgation.getType().getTypeId());
                BasePromulgationService service = instantiatePromulgationService(type.getServiceId());
                if (service != null) {
                    service.resetMessagePromulgation(message, type);
                } else {
                    log.warn("Unable to instantiate promulgation service for type " + type.getTypeId());
                }
            }
        }
    }


    /**
     * Updates all referenced promulgation types with the persisted versions and returns the set of types
     * @param message the message to update
     * @return the associated promulgation types
     */
    private Set<PromulgationType> persistedPromulgationTypes(Message message) {
        Set<PromulgationType> types = new HashSet<>();
        message.getPromulgations()
                .forEach(p -> {
                    p.setType(persistedPromulgationType(p.getType().getTypeId()));
                    types.add(p.getType());
                });
        return types;
    }

    /**
     * Returns the persisted promulgation type with the given ID.
     * <p>
     * An unknown ID is refused with an unchecked exception, as the lookups of {@code PromulgationTypeService}
     * refuse one: a checked exception would leave the caller's transaction to commit what a message save had
     * changed before the lookup.
     * @param typeId the promulgation type ID
     * @return the persisted promulgation type
     * @throws IllegalArgumentException if no promulgation type has the ID
     */
    private PromulgationType persistedPromulgationType(String typeId) {
        PromulgationType type = promulgationTypeService.getPromulgationType(typeId);
        if (type == null) {
            throw new IllegalArgumentException("No promulgation type with the ID " + typeId);
        }
        return type;
    }

    /***************************************/
    /** Utility function                  **/
    /***************************************/

    /**
     * Instantiates the promulgation service bean for the promulgation type.
     * Returns null if the class could not be instantiated
     * @param type the promulgation service type
     */
    private BasePromulgationService instantiatePromulgationService(String type) {
        return instantiatePromulgationService(serviceRegistry.get(type));
    }

    /**
     * Instantiates the promulgation service bean for the given class.
     * Returns null if the class could not be instantiated
     *
     * @param promulgationServiceClass the promulgation service class
     */
    // PORT: the Quarkus version matched on InstanceHandle.getBean().getBeanClass()
    // against promulgationServiceClass.getSuperclass() to unwrap the Quarkus client
    // proxy. Under Spring the promulgation services carry @Transactional and are
    // therefore CGLIB-proxied, and the two registration paths (the @PostConstruct
    // initServiceRegistry seeding from the injected proxy list, and the inherited
    // @EventListener init() running on the *target* instance) can store different
    // Class objects for the same service. A raw getClass() equality check then
    // misses the proxy in `services`, so resolve both sides to their user (unproxied)
    // class via Spring's ClassUtils.getUserClass before comparing.
    private <T> BasePromulgationService instantiatePromulgationService(Class<T> promulgationServiceClass) {
        // Sanity Check
        if(Objects.isNull(promulgationServiceClass)) {
            return null;
        }
        Class<?> targetClass = org.springframework.util.ClassUtils.getUserClass(promulgationServiceClass);
        return this.services.stream()
                .filter(service -> org.springframework.util.ClassUtils.getUserClass(service.getClass()).equals(targetClass))
                .findFirst()
                .orElse(null);
    }

}
