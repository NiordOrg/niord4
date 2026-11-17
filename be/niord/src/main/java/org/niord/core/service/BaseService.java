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
package org.niord.core.service;

import org.hibernate.query.sqm.NodeBuilder;
import org.niord.core.model.BaseEntity;
import org.niord.core.web.NiordWebException;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import org.springframework.transaction.annotation.Transactional;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DAO-like base class for services that work on work on {@linkplain BaseEntity}
 */
@Transactional
@SuppressWarnings("unused")
public abstract class BaseService {

    @Inject
    protected EntityManager em;

    /**
     * Constructor
     */
    protected BaseService() {
    }

    /**
     * Constructor
     *
     * @param entityManager the ent
     */
    protected BaseService(EntityManager entityManager) {
        this.em = entityManager;
    }

    /**
     * Returns the entity with the given id or {@code null} if none is found
     *
     * @param clazz the entity class
     * @param id the id of the entity
     * @return the entity with the given id or {@code null} if none is found
     */
    public <E extends BaseEntity> E getByPrimaryKey(Class<E> clazz, Object id) {
        try {
            return em.find(clazz, id);
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the entity with the given id, or answers 404 when none is found: for a write that names an entity by
     * its id, where an unknown id is the caller's mistake and not a server fault
     *
     * @param clazz the entity class
     * @param id the id of the entity
     * @return the entity with the given id
     * @throws NiordWebException with status 404 when no entity has the id
     */
    public <E extends BaseEntity> E requireByPrimaryKey(Class<E> clazz, Object id) {
        E entity = getByPrimaryKey(clazz, id);
        if (entity == null) {
            throw new NiordWebException("No " + clazz.getSimpleName() + " with id " + id, 404);
        }
        return entity;
    }

    /**
     * Returns the persistence entity manager as a hibernate node builder which
     * can be used to generate SQM expressions.
     *
     * @return the Hibernate SQM node builder
     */
    protected NodeBuilder getNodeBuilder() {
        NodeBuilder nodeBuilder = (NodeBuilder) em.getCriteriaBuilder();
        
        return nodeBuilder;
//        return Optional.of(em)
//                .filter(NodeBuilder.class::isInstance)
//                .map(NodeBuilder.class::cast)
//                .orElse(null);
    }

    /**
     * Removes the given entity from the database
     *
     * @param entity the entity to remove
     */
    public void remove(BaseEntity entity) {

        em.remove(em.merge(entity));
    }

    /**
     * Persists or updates the given entity
     *
     * @param entity the entity to persist or update
     * @return the updated entity
     */
    public <E extends BaseEntity> E saveEntity(E entity) {
        if (entity.isPersisted()) {
            // Update existing
            entity = em.merge(entity);
        } else {
            // Save new
            em.persist(entity);
        }
        return entity;
    }

    /**
     * Returns the first element of the list, or {@code null} if the list is empty or {@code null}
     *
     * @param list the list
     * @return the first element
     */
    public static <T> T getSingleOrNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    /**
     * Returns all entities with the given class
     *
     * @param entityType the class
     * @return all entities with the given class
     */
    public <E extends BaseEntity> List<E> getAll(Class<E> entityType) {
        em.clear();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<E> cq = cb.createQuery(entityType);
        cq.from(entityType);
        return em.createQuery(cq).getResultList();
    }

    /**
     * Counts all entities with the given class
     *
     * @param entityType the class
     * @return the number of entities with the given class
     */
    public <E extends BaseEntity> long count(Class<E> entityType) {
        em.clear();

        CriteriaBuilder qb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = qb.createQuery(Long.class);
        cq.select(qb.count(cq.from(entityType)));
        return em.createQuery(cq).getSingleResult();
    }

    /**
     * Returns a list of persisted entities
     * @param entityType the class
     * @param entities the list of entities to look up persisted entities for
     * @return the list of corresponding persisted entities
     */
    public <E extends BaseEntity> List<E> persistedList(Class<E> entityType, List<E> entities) {
        return entities.stream()
                .filter(e -> e.getId() != null)
                .map(e -> getByPrimaryKey(entityType, e.getId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns a list of persisted entities
     * @param entityType the class
     * @param entities the list of entities to look up persisted entities for
     * @return the list of corresponding persisted entities
     */
    public <E extends BaseEntity> Set<E> persistedSet(Class<E> entityType, Set<E> entities) {
        return entities.stream()
                .filter(e -> e.getId() != null)
                .map(e -> getByPrimaryKey(entityType, e.getId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Returns a list of persisted entities for the given IDs
     * @param entityType the class
     * @param ids the list of IDs to look up persisted entities for
     * @return the list of corresponding persisted entities
     */
    public <ID extends Serializable, E extends BaseEntity<ID>> List<E> persistedListForIds(Class<E> entityType, List<Integer> ids) {
        return ids.stream()
                .map(id -> getByPrimaryKey(entityType, id))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Checks if two entities have the same ID, catering with null parameters
     * @param e1 the first entity
     * @param e2 the second entity
     * @return if two entities have the same ID, catering with null parameters
     */
    public static <E extends BaseEntity> boolean sameEntities(E e1, E e2) {
        if (e1 == null && e2 == null) {
            return true;
        } else if (e1 == null || e2 == null) {
            return false;
        }
        return Objects.equals(e1.getId(), e2.getId());
    }

    /**
     * Checks if two lists of entities are identical by matching IDs
     * @param l1 the first list of entities
     * @param l2 the second list of entities
     * @param sameOrder if the order is significant
     * @return if two lists of entities are identical by matching IDs
     */
    public static <E extends BaseEntity> boolean sameEntities(List<E> l1, List<E> l2, boolean sameOrder) {
        if (l1 == null && l2 == null) {
            return true;
        } else if (l1 == null || l2 == null || l1.size() != l2.size()) {
            return false;
        }
        if (sameOrder) {
            for (int x = 0; x < l1.size(); x++) {
                if (!sameEntities(l1.get(x), l2.get(x))) {
                    return false;
                }
            }
            return true;
        } else {
            // Unordered comparison: the lists are "the same" only when they hold the same ids with the
            // same multiplicities (a multiset compare). The previous set-of-ids + allMatch ignored
            // duplicates and was asymmetric - it reported e.g. [1,2] and [1,1] as equal, which could make
            // change-detection skip a needed persist (BASESVC2). The sizes are already equal here, so we
            // remove each l2 id from a copy of l1's ids and require every removal to succeed.
            // List.remove(Object) tolerates a null id (a transient entity), matching the null-tolerant
            // scalar sameEntities(E, E) - unlike Collectors.groupingBy, which rejects a null key.
            List<Object> remaining = new ArrayList<>(l1.size());
            for (E e : l1) {
                remaining.add(e.getId());
            }
            for (E e : l2) {
                if (!remaining.remove(e.getId())) {
                    return false;
                }
            }
            return remaining.isEmpty();
        }
    }

}
