package net.researchgate.restdsl.dao;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.mongodb.DuplicateKeyException;
import net.researchgate.restdsl.domain.EntityIndexInfo;
import net.researchgate.restdsl.domain.EntityInfo;
import net.researchgate.restdsl.exceptions.RestDslException;
import net.researchgate.restdsl.metrics.NoOpStatsReporter;
import net.researchgate.restdsl.metrics.StatsReporter;
import net.researchgate.restdsl.metrics.StatsTimingWrapper;
import net.researchgate.restdsl.queries.ServiceQuery;
import net.researchgate.restdsl.queries.ServiceQueryInfo;
import net.researchgate.restdsl.queries.ServiceQueryReservedValue;
import net.researchgate.restdsl.results.EntityList;
import net.researchgate.restdsl.results.EntityMultimap;
import net.researchgate.restdsl.results.EntityResult;
import net.researchgate.restdsl.util.ServiceQueryUtil;
import net.researchgate.restdsl.types.TypeInfoUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.dao.BasicDAO;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.UpdateOperations;
import org.mongodb.morphia.query.UpdateResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MongoServiceDao<V, K> implements PersistentServiceDao<V, K> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoServiceDao.class);

    private static final String QUERY_KEY = "queries.shapes.%s.%%H";
    private final String collectionName;

    private BasicDAO<V, K> morphiaDao;
    private Class<V> entityClazz;
    private EntityInfo<V> entityInfo;
    private EntityIndexInfo<V> entityIndexInfo;

    private StatsReporter statsReporter;

    //TODO: provide implementations for StatsReporter in example service


    public MongoServiceDao(Datastore datastore, Class<V> entityClazz) {
        this(datastore, entityClazz, NoOpStatsReporter.INSTANCE);
    }

    public MongoServiceDao(Datastore datastore, Class<V> entityClazz, StatsReporter statsReporter) {
        this.morphiaDao = new BasicDAO<>(entityClazz, datastore);
        this.entityClazz = entityClazz;
        this.entityInfo = EntityInfo.get(entityClazz);
        this.entityIndexInfo = new EntityIndexInfo<>(entityClazz, morphiaDao.getCollection().getIndexInfo());
        this.collectionName = morphiaDao.getCollection().getName();
        this.statsReporter = statsReporter;
    }

    @Override
    public EntityResult<V> get(ServiceQuery<K> serviceQuery) throws RestDslException {
        Query<V> morphiaQuery = convertToMorphiaQuery(serviceQuery);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(morphiaQuery.toString());
        }

        String groupBy = serviceQuery.getGroupBy();
        try (StatsTimingWrapper ignored = getQueryShapeWrapper(serviceQuery)) {
            if (groupBy == null) {
                List<V> results = Collections.emptyList();
                if (!serviceQuery.getCountOnly()) {
                    results = morphiaDao.find(morphiaQuery).asList();
                }
                return new EntityResult<>(results, getTotalItemsCnt(morphiaQuery, serviceQuery, results));
            } else {
                // warning: in-place criteria editing
                Collection<Object> criteriaForGrouping = Lists.newArrayList(serviceQuery.getCriteria().get(groupBy));

                Map<Object, EntityList<V>> groupedResult = new HashMap<>();
                for (Object k : criteriaForGrouping) {
                    serviceQuery.getCriteria().removeAll(groupBy);
                    serviceQuery.getCriteria().put(groupBy, k);
                    Query<V> q = convertToMorphiaQuery(serviceQuery);
                    List<V> resultPerKey = Collections.emptyList();
                    if (!serviceQuery.getCountOnly()) {
                        resultPerKey = morphiaDao.find(q).asList();
                    }

                    EntityList<V> entityList = new EntityList<>(resultPerKey, getTotalItemsCnt(q, serviceQuery, resultPerKey));
                    groupedResult.put(k, entityList);
                }
                return new EntityResult<>(new EntityMultimap<>(groupedResult, serviceQuery.isCountTotalItems() ? morphiaQuery.countAll() : null));
            }
        }
    }


    @Override
    public V getOne(ServiceQuery<K> serviceQuery) throws RestDslException {
        return morphiaDao.findOne(convertToMorphiaQuery(serviceQuery));
    }

    @Override
    public long count(ServiceQuery<K> serviceQuery) throws RestDslException {
        return convertToMorphiaQuery(serviceQuery).countAll();
    }

    @Override
    public int delete(ServiceQuery<K> serviceQuery) throws RestDslException {
        if ((serviceQuery.getCriteria() == null || serviceQuery.getCriteria().isEmpty())
                && CollectionUtils.isEmpty(serviceQuery.getIdList())) {
            throw new RestDslException("Deletion query should either provide ids or criteria", RestDslException.Type.QUERY_ERROR);
        }
        preDelete(serviceQuery);
        return morphiaDao.deleteByQuery(convertToMorphiaQuery(serviceQuery, false)).getN();
    }

    @Override
    public V save(V entity) {
        prePersist(entity);
        try {
            morphiaDao.save(entity);
        } catch (DuplicateKeyException e) {
            throw new RestDslException("Duplicate mongo key: " + e.getMessage(), RestDslException.Type.DUPLICATE_KEY);
        }
        return entity;
    }

    @Override
    public V patch(ServiceQuery<K> q, Map<String, Object> patchedFields) throws RestDslException {
        UpdateOperations<V> ops = createUpdateOperations();
        for (Map.Entry<String, Object> e : patchedFields.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value != null) {
                ops.set(key, value);
            } else {
                ops.unset(key);
            }
        }

        return findAndModify(q, ops);
    }

    protected UpdateOperations<V> createUpdateOperations() {
        return morphiaDao.createUpdateOperations();
    }

    protected UpdateResults update(ServiceQuery<K> q, UpdateOperations<V> updateOperations) throws RestDslException {
        preUpdate(q, updateOperations);
        return morphiaDao.update(convertToMorphiaQuery(q, false), updateOperations);
    }

    protected V findAndModify(ServiceQuery<K> q, UpdateOperations<V> updateOperations) throws RestDslException {
        preUpdate(q, updateOperations);
        Query<V> morphiaQuery = convertToMorphiaQuery(q, false);
        try {
            return morphiaDao.getDatastore().findAndModify(morphiaQuery, updateOperations, false, false);
        } catch (DuplicateKeyException e) {
            throw new RestDslException("Duplicate mongo key: " + e.getMessage(), RestDslException.Type.DUPLICATE_KEY);
        }
    }

    protected Query<V> convertToMorphiaQuery(ServiceQuery<K> serviceQuery) throws RestDslException {
        return convertToMorphiaQuery(serviceQuery, true);
    }

    protected Query<V> convertToMorphiaQuery(ServiceQuery<K> serviceQuery, boolean getQuery) throws RestDslException {
        validateQuery(serviceQuery);

        Query<V> mongoQuery = morphiaDao.createQuery();

        Collection<K> ids = serviceQuery.getIdList();
        if (ids != null) {
            if (ids.size() == 1) {
                mongoQuery.field(entityInfo.getIdFieldName()).equal(ids.iterator().next());
            } else {
                mongoQuery.field(entityInfo.getIdFieldName()).hasAnyOf(ids);
            }
        }

        if (getQuery) {
            if (serviceQuery.getFields() != null) {
                boolean all = false;
                for (String f : serviceQuery.getFields()) {
                    if (f.equals("*")) {
                        all = true;
                    }
                }
                if (!all) {
                    mongoQuery.retrievedFields(true, serviceQuery.getFields().toArray(new String[serviceQuery.getFields().size()]));
                }
            }

            mongoQuery.offset(serviceQuery.getOffset());
            mongoQuery.limit(serviceQuery.getLimit());

            if (serviceQuery.getOrder() != null) {
                mongoQuery.order(serviceQuery.getOrder());
            }
        }


        if (serviceQuery.getCriteria() != null) {
            Multimap<String, String> syncMatchToCriteriaKeys = HashMultimap.create();
            Set<String> syncMatch = serviceQuery.getSyncMatch() == null ? Collections.emptySet() : serviceQuery.getSyncMatch();

            for (String k : serviceQuery.getCriteria().keySet()) {
                boolean forSyncMatch = false;
                for (String sm : syncMatch) {
                    if (k.startsWith(sm + ".")) {
                        syncMatchToCriteriaKeys.put(sm, k);
                        forSyncMatch = true;
                    }
                }

                if (forSyncMatch) {
                    continue;
                }

                Collection<Object> objs = serviceQuery.getCriteria().get(k);
                enrichQuery(mongoQuery, k, objs);
            }

            if (!syncMatchToCriteriaKeys.isEmpty()) {
                for (String k : syncMatchToCriteriaKeys.keySet()) {
                    Collection<String> criteriaKeys = syncMatchToCriteriaKeys.get(k);

                    Pair<Class<?>, Class<?>> fieldExpressionClazz = TypeInfoUtil.getFieldExpressionClazz(entityClazz, k);
                    Query<?> subFieldQuery = morphiaDao.getDatastore().createQuery(fieldExpressionClazz.getLeft());
                    for (String criteriaKey : criteriaKeys) {
                        enrichQuery(subFieldQuery, criteriaKey.substring(k.length() + 1), serviceQuery.getCriteria().get(criteriaKey));
                    }

                    mongoQuery.field(k).hasThisElement(subFieldQuery.getQueryObject());
                }
            }
        }

        return mongoQuery;
    }

    private void enrichQuery(Query<?> mongoQuery, String field, Collection<Object> criteria) {
        if (criteria.size() == 1) {
            // range query
            Object val = criteria.iterator().next();
            if (field.contains(">") || field.contains("<")) {
                mongoQuery.filter(field, val);
            } else if (val instanceof ServiceQueryReservedValue) {
                adaptToReservedValue(mongoQuery, field, (ServiceQueryReservedValue) val);
            } else {
                mongoQuery.field(field).equal(val);
            }
        } else {
            mongoQuery.field(field).in(criteria);
        }
    }

    // optimization. If the returned set is smaller than limit, that means we can calculate size without countAll()
    private Long getTotalItemsCnt(Query<V> q, ServiceQuery<?> serviceQuery, List<V> results) {
        if (!serviceQuery.isCountTotalItems()) {
            return null;
        }

        if (results.size() > q.getLimit()) {
            throw new RestDslException("Implementation error: results size must be not greater than limit, was " +
                    results.size() + " but limit was: " + q.getLimit());
        }

        if (serviceQuery.getCountOnly()) {
            return q.countAll();
        }

        // if getLimit == 0 then we need to count anyway
        if (results.size() == 0 && q.getOffset() == 0 && q.getLimit() > 0) {
            return 0L;
        }

        // if size is equal 0 it could be that offset is too big, or we just have 0 elements in total - must count
        if (results.size() != 0 && results.size() < q.getLimit()) {
            return (long) (q.getOffset() + results.size());
        } else {
            return q.countAll();
        }
    }

    private void adaptToReservedValue(Query<?> mongoQuery, String k, ServiceQueryReservedValue val) {
        if (val == ServiceQueryReservedValue.EXISTS) {
            mongoQuery.criteria(k).exists();
        } else if (val == ServiceQueryReservedValue.NULL) {
            mongoQuery.field(k).equal(null);
        } else if (val == ServiceQueryReservedValue.ANY) {
            // no op
        } else {
            throw new RestDslException("Unhandled reserved value: " + val, RestDslException.Type.GENERAL_ERROR);
        }
    }

    @Override
    public void validateQuery(ServiceQuery<K> serviceQuery) throws RestDslException {
        if (serviceQuery.isIndexValidation()) {
            boolean safeQuery = isSafeQuery(serviceQuery);
            if (!safeQuery) {
                throw new RestDslException("Query criterion for fields " + serviceQuery.getCriteria().keySet() +
                        " don't match declared indexes  [(" + Joiner.on("), (").join(entityIndexInfo.getIndexesMap()) +
                        ")] for class " + entityClazz.getName() +
                        "; use '?indexValidation=false' query parameter to temporarily disable it for debugging purposes.",
                        RestDslException.Type.QUERY_ERROR);
            }
        }

        if (serviceQuery.getLimit() < 0) {
            throw new RestDslException("Query limit must be positive", RestDslException.Type.QUERY_ERROR);
        }

        // for primary keys it could also works, but it does not make sense to group on them since they are unique
        if (serviceQuery.getGroupBy() != null &&
                (serviceQuery.getCriteria() == null || !serviceQuery.getCriteria().containsKey(serviceQuery.getGroupBy()))) {
            throw new RestDslException("When provided, groupBy parameter should be contained in query criteria",
                    RestDslException.Type.QUERY_ERROR);
        }

    }

    @Override
    public ServiceQueryInfo<K> getServiceQueryInfo(ServiceQuery<K> serviceQuery) {
        return new ServiceQueryInfo<>(serviceQuery, isSafeQuery(serviceQuery));
    }

    private boolean isSafeQuery(ServiceQuery<K> serviceQuery) {
        boolean queryIsSafe = true;
        // criteria is not empty and primary keys are not specified, then we need to check whether index is used
        boolean shouldCheckForIndex = serviceQuery.getCriteria() != null && !serviceQuery.getCriteria().isEmpty()
                && CollectionUtils.isEmpty(serviceQuery.getIdList());

        if (shouldCheckForIndex) {
            queryIsSafe = false;
            for (String field : serviceQuery.getCriteria().keySet()) {
                if (entityIndexInfo.getIndexPrefixMap().contains(ServiceQueryUtil.parseQueryField(field).getFieldName())) {
                    queryIsSafe = true;
                    break;
                }
            }
        }
        return queryIsSafe;
    }

    /**
     * Use only when necessary.
     *
     * @return morphia's dao
     * @deprecated Use ServiceQuery when possible
     */
    public BasicDAO<V, K> getMorphiaDaoUnsafe() {
        return morphiaDao;
    }

    @Override
    public void prePersist(V entity) {
        // no op
    }

    @Override
    public void preUpdate(ServiceQuery<K> q, UpdateOperations<V> updateOperations) {
        // no op
    }

    @Override
    public void preDelete(ServiceQuery<K> q) {
        // no op
    }

    // PRIVATE
    private StatsTimingWrapper getQueryShapeWrapper(ServiceQuery<K> serviceQuery) {
        return StatsTimingWrapper.of(statsReporter, String.format(QUERY_KEY, collectionName + "." + serviceQuery.getQueryShape()));
    }

}
