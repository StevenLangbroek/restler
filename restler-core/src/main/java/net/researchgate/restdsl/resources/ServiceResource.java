package net.researchgate.restdsl.resources;

import com.mongodb.BasicDBObject;
import net.researchgate.restdsl.annotations.PATCH;
import net.researchgate.restdsl.domain.EntityInfo;
import net.researchgate.restdsl.exceptions.RestDslException;
import net.researchgate.restdsl.model.ServiceModel;
import net.researchgate.restdsl.queries.ServiceQuery;
import net.researchgate.restdsl.queries.ServiceQueryInfo;
import net.researchgate.restdsl.queries.ServiceQueryParams;
import net.researchgate.restdsl.results.EntityResult;
import net.researchgate.restdsl.types.TypeInfoUtil;
import net.researchgate.restdsl.util.RequestUtil;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.ParameterizedType;

import static javax.ws.rs.core.Response.Status.CREATED;


/**
 * Created by zholudev on 14/04/16.
 * Commons methods for CRUD
 * V - entity type
 * K - entity primary key type
 */
public abstract class ServiceResource<V, K> {
    private final EntityInfo<V> entityInfo;
    private final Class<V> entityClazz;
    private final Class<K> idClazz;
    private final ServiceModel<V, K> serviceModel;

    public ServiceResource(ServiceModel<V, K> serviceModel, Class<V> entityClazz, Class<K> idClazz) {
        this.serviceModel = serviceModel;
        this.entityClazz = entityClazz;
        this.idClazz = idClazz;
        entityInfo = EntityInfo.get(entityClazz);
    }

    @SuppressWarnings("unchecked")
    public ServiceResource(ServiceModel<V, K> serviceModel) throws RestDslException {
        Class<? extends ServiceModel> serviceModelClazz = serviceModel.getClass();
        if (serviceModelClazz == ServiceModel.class || serviceModelClazz.getSuperclass() != ServiceModel.class) {
            throw new RestDslException("Unable to detect entity and key type from class " + serviceModelClazz.getName() +
                    "; use constructor with explicit entity and key classes",
                    RestDslException.Type.GENERAL_ERROR);
        }

        ParameterizedType t = (ParameterizedType) serviceModelClazz.getAnnotatedSuperclass().getType();
        this.entityClazz = (Class<V>) t.getActualTypeArguments()[0];
        this.idClazz = (Class<K>) t.getActualTypeArguments()[1];
        entityInfo = EntityInfo.get(entityClazz);
        this.serviceModel = serviceModel;
    }

    @Path("/{segment: .*}")
    @GET
    @Produces("application/json")
    public EntityResult<V> getEntityResult(@PathParam("segment") PathSegment segment, @Context UriInfo uriInfo) throws RestDslException {
        ServiceQuery<K> query = getQueryFromRequest(segment, uriInfo);
        return serviceModel.get(query);
    }

    @Path("/{segment: .*}/info")
    @GET
    @Produces("application/json")
    public ServiceQueryInfo<K> getQueryInfo(@PathParam("segment") PathSegment segment, @Context UriInfo uriInfo) throws RestDslException {
        ServiceQuery<K> query = getQueryFromRequest(segment, uriInfo);
        return serviceModel.getServiceQueryInfo(query);
    }

    @PATCH
    public V patchEntity(V entity) throws RestDslException {
        validatePatchEntity(entity);
        return serviceModel.patch(entity);
    }

    @POST
    public Response createEntity(V entity) throws RestDslException {
        validatePostEntity(entity);
        V persisted = serviceModel.save(entity);
        return Response.status(CREATED).entity(persisted).build();
    }

    @PUT
    @Path("/{id: .*}")
    public Response updateEntity(@PathParam("id") String id, V entity) throws RestDslException {
        K key = getId(id);
        if (key == null) {
            throw new RestDslException("Key cannot be null", RestDslException.Type.PARAMS_ERROR);
        }
        validatePut(key, entity);

        entityInfo.setIdFieldValue(entity, key);
        V persisted = serviceModel.save(entity);
        return Response.status(CREATED).entity(persisted).build();
    }

    protected K getId(String id) throws RestDslException {
        return TypeInfoUtil.getValue(id, EntityInfo.get(entityClazz).getIdFieldName(), idClazz, entityClazz);
    }

    @Path("/{segment: .*}")
    @DELETE
    @Produces("application/json")
    public String delete(@PathParam("segment") PathSegment segment, @Context UriInfo uriInfo) throws RestDslException {
        ServiceQuery<K> query = getQueryFromRequest(segment, uriInfo);
        int deleted = serviceModel.delete(query);
        return new BasicDBObject("deleted", deleted).toString();
    }


    protected ServiceQuery<K> getQueryFromRequest(PathSegment segment, UriInfo uriInfo) throws RestDslException {
        return RequestUtil.parseRequest(entityClazz, idClazz, segment, uriInfo, getServiceQueryParams());
    }


    protected void validatePostEntity(V entity) throws RestDslException {
        // override if you need extra validation
    }

    protected void validatePatchEntity(V entity) throws RestDslException {
        K val = entityInfo.getIdFieldValue(entity);
        if (val == null) {
            throw new RestDslException("Id must be provided when creating a new entity, but was null",
                    RestDslException.Type.ENTITY_ERROR);
        }
    }

    protected void validatePut(K key, V entity) throws RestDslException {
        K val = entityInfo.getIdFieldValue(entity);
        if (val != null && !val.equals(key)) {
            throw new RestDslException("Id either should not be provided or be equal to the one in the entity, " +
                    "but was: " + val + " vs " + key, RestDslException.Type.ENTITY_ERROR);
        }
    }

    protected ServiceQueryParams getServiceQueryParams() {
        return ServiceQueryParams.DEFAULT_QUERY_PARAMS;
    }

    // HELPERS
//    public <K, V> ServiceQuery<K> parseRequest(Class<V> entityClazz, Class<K> idClazz, PathSegment segment, UriInfo uriInfo) throws RestDslException {
//
//        ServiceQuery.ServiceQueryBuilder<K> builder = ServiceQuery.builder();
//
//        builder.offset(getInt("offset", uriInfo));
//        builder.limit(getInt("limit", uriInfo));
//        builder.fields(getToList("fields", uriInfo));
//        builder.order(uriInfo.getQueryParameters().getFirst("order"));
//        builder.indexValidation(getBoolean("indexValidation", uriInfo));
//        builder.countTotalItems(getBoolean("countTotalItems", uriInfo));
//        builder.groupBy(getString("groupBy", uriInfo));
//        builder.withServiceQueryParams(getServiceQueryParams());
//        builder.syncMatch(getToList("syncMatch", uriInfo));
//
//        MultivaluedMap<String, String> matrixParams = segment.getMatrixParameters();
//        if (!matrixParams.isEmpty()) {
//            for (String fieldNameWithCriteria : matrixParams.keySet()) {
//                Collection<String> values = matrixParams.get(fieldNameWithCriteria);
//                // splitting comma-separated values
//                Collection<String> splitValues = new ArrayList<>();
//                for (String v : values) {
//                    splitValues.addAll(Splitter.on(',').omitEmptyStrings().splitToList(v));
//                }
//                ServiceQueryUtil.ParsedQueryField parsedQueryField = ServiceQueryUtil.parseQueryField(fieldNameWithCriteria);
//                String fieldNameWithoutConditions = parsedQueryField.getFieldName();
//                Pair<Class<?>, Class<?>> pairOfFieldClazzAndParentClazz =
//                        TypeInfoUtil.getFieldExpressionClazz(entityClazz, fieldNameWithoutConditions);
//
//                Class<?> fieldClazz = pairOfFieldClazzAndParentClazz.getLeft();
//                Class<?> parentClazz = pairOfFieldClazzAndParentClazz.getRight();
//
//                Stream<?> criteria = splitValues.stream().map(input -> TypeInfoUtil.getValue(input, fieldNameWithoutConditions, fieldClazz, parentClazz));
//                builder.withCriteria(parsedQueryField.getFullCriteria(), criteria.collect(Collectors.toList()));
//
//            }
//        }
//        if (!StringUtils.isEmpty(segment.getPath()) && !segment.getPath().startsWith("-")) {
//            builder.ids(Lists.transform(Splitter.on(',').splitToList(segment.getPath()),
//                    input -> TypeInfoUtil.getValue(input, EntityInfo.get(entityClazz).getIdFieldName(), idClazz, entityClazz)));
//        }
//
//        return builder.build();
//    }

}
