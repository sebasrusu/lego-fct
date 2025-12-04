package cc.srv.resources;

import cc.data.comment.Comment;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSet;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
import cc.db.CosmosDBLayer;
import cc.db.RedisLayer;
import com.azure.cosmos.CosmosException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/legoset")
public class LegoSetResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();
    private static final Logger LOG = Logger.getLogger(LegoSetResource.class.getName());
    // ADDED: redis instance + mapper used by caching code
    private final RedisLayer redis = RedisLayer.getInstance();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createLegoSet(LegoSet legoSet) {
        if (legoSet.getId() == null || legoSet.getId().isEmpty()) {
            legoSet.setId(UUID.randomUUID().toString());
        }
        // Validação para garantir que o ownerId foi fornecido
        if (legoSet.getOwnerId() == null || legoSet.getOwnerId().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("LegoSet must have an ownerId.").build();
        }

        //garantir que o owner existe (se id está presente na tablea dos users)
        UserDAO owner = db.getUser(legoSet.getOwnerId());
        if (owner == null)
            return Response.status(Response.Status.BAD_REQUEST).entity("User does not exist.").build();


        try {
            LegoSetDAO result = db.createLegoSet(new LegoSetDAO(legoSet));

            String[] legoIds = owner.getLegoIds();
            if (legoIds == null) legoIds = new String[0];

            // evitar duplicados
            boolean exists = Arrays.asList(legoIds).contains(result.getId());
            if (!exists) {
                String[] updated = Arrays.copyOf(legoIds, legoIds.length + 1);
                updated[legoIds.length] = result.getId();
                legoIds = updated;
                owner.setLegoIds(legoIds);
                db.updateUser(owner);
            }

            // --- INVALIDAÇÃO DA CACHE ---
            LOG.info("Invalidando cache para legosets:list (createLegoSet)");
            redis.deleteKey("legosets:list");
            // -----------------------------

            return Response.created(URI.create("/legoset/" + result.getId()))
                    .entity(result.toLegoSet())
                    .build();
        } catch (CosmosException e) {
            if (e.getStatusCode() == 409) {
                return Response.status(Response.Status.CONFLICT).entity("LegoSet already exists.").build();
            }
            LOG.log(Level.SEVERE, "Erro CosmosDB ao criar LegoSet", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Erro genérico ao criar LegoSet", e);
            return Response.serverError().entity("Erro ao criar LegoSet: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLegoSet(@PathParam("id") String id) {
        // NOTA: Cache para um LegoSet individual não foi implementada,
        // mas seria uma boa otimização (getLegoSetById(id), setLegoSetById(id))
        LegoSetDAO dao = db.getLegoSet(id);
        return dao != null ? Response.ok(dao.toLegoSet()).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<LegoSet> listLegoSets(@QueryParam("userId") String userId, @QueryParam("recent") String recent, @QueryParam("st") @DefaultValue("0") int st, @QueryParam("len") @DefaultValue("20") int len) {

        // Caminho 1: Por User (Sem cache neste exemplo)
        if (userId != null && !userId.isEmpty()) {
            LOG.info("A processar listLegoSets para userId: " + userId);
            return StreamSupport.stream(db.listLegoSetsOfUser(userId).spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }

        // Caminho 2: Recentes (Sem cache neste exemplo)
        if(recent != null) {
            LOG.info("A processar listLegoSets para recent: " + st + ", " + len);
            return StreamSupport.stream(db.listMostRecentLegoSets(st, len).spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }

        // --- CACHE-ASIDE: Listar Todos ---
        LOG.info("A processar listLegoSets (todos)");
        String cacheKey = "legosets:list";

        // 1. Tentar obter da cache
        List<LegoSet> cachedSets = Collections.emptyList();
        try {
             cachedSets = redis.getLegosetsList();
        } catch (Exception e) {
             LOG.log(Level.WARNING, "Falha ao LER legosets:list do Redis", e);
        }

        if (cachedSets != null && !cachedSets.isEmpty()) {
            LOG.info("Cache HIT para legosets:list");
            return cachedSets;
        }

        // 2. Cache miss: Ir à base de dados
        List<LegoSet> legoSets;
        try {
            LOG.info("Cache MISS para legosets:list. A consultar Cosmos DB.");
            legoSets = StreamSupport.stream(db.listLegoSets().spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Falha ao consultar/mapear legosets da Cosmos DB", e);
            legoSets = Collections.emptyList();
        }

        // 3. Guardar na cache
        try {
            LOG.info("A guardar legosets:list na cache.");
            redis.setLegosetsList(legoSets);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao GUARDAR legosets:list no Redis", e);
        }

        return legoSets;
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateLegoSet(@PathParam("id") String id, LegoSet legoSet) {
        if (!id.equals(legoSet.getId())) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            db.updateLegoSet(new LegoSetDAO(legoSet));

            // --- INVALIDAÇÃO DA CACHE ---
            LOG.info("Invalidando cache para legosets:list (updateLegoSet)");
            redis.deleteKey("legosets:list");
            // -----------------------------

            return Response.ok().build();
        } catch (Exception e) {
             LOG.log(Level.SEVERE, "Falha ao atualizar legoset", e);
             return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteLegoSet(@PathParam("id") String id) {
        try {
            db.deleteLegoSet(id);

            // --- INVALIDAÇÃO DA CACHE ---
            LOG.info("Invalidando cache para legosets:list (deleteLegoSet)");
            redis.deleteKey("legosets:list");
            // Também apaga a cache de comentários desse lego (se existir)
            LOG.info("Invalidando cache para comments:lego:" + id);
            redis.deleteCommentsForLego(id);
            // -----------------------------

            return Response.noContent().build();
        } catch (Exception e) {
             LOG.log(Level.SEVERE, "Falha ao apagar legoset", e);
             return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/{id}/comment")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Comment> listComments(@PathParam("id") String legoSetId) {
        // use the same key prefix as RedisLayer
        String cacheKey = "comments:lego:" + legoSetId;
        try {
            if (redis != null && redis.isAvailable()) {
                String cached = redis.getValue(cacheKey);
                if (cached != null) {
                    // return cached JSON as List<Comment>
                    Comment[] arr = mapper.readValue(cached, Comment[].class);
                    return Arrays.asList(arr);
                }
            }
        } catch (Exception e) {
            LOG.warning("Redis cache read failed: " + e.getMessage());
        }

        // fallback to Cosmos DB
        List<Comment> fromDb = StreamSupport.stream(db.listCommentsByLegoSet(legoSetId).spliterator(), false)
                .map(CommentDAO::toComment)
                .collect(Collectors.toList());

        try {
            if (redis != null && redis.isAvailable()) {
                String json = mapper.writeValueAsString(fromDb);
                redis.putValue(cacheKey, json);
            }
        } catch (Exception e) {
            LOG.warning("Redis cache write failed: " + e.getMessage());
        }
        return fromDb;
    }

    @POST
    @Path("/{id}/comment")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createComment(@PathParam("id") String legoSetId, Comment comment) {
        try {
            if (comment == null) return Response.status(Response.Status.BAD_REQUEST).entity("comment obrigatório").build();
            if (comment.getId() == null || comment.getId().isBlank())
                comment.setId(UUID.randomUUID().toString());
            comment.setLegoSetId(legoSetId);

            db.createComment(new CommentDAO(comment));

            if (redis.isAvailable()) redis.deleteCommentsForLego(legoSetId);

            URI uri = URI.create(String.format("/legoset/%s/comment/%s", legoSetId, comment.getId()));
            return Response.created(uri).entity(comment).build();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Erro criar comentário " + legoSetId, e);
            return Response.serverError().build();
        }
    }
}