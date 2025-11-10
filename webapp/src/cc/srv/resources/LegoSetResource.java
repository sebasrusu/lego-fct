package cc.srv.resources;

import cc.data.comment.Comment;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSet;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
import cc.db.CosmosDBLayer;
import cc.db.RedisLayer; // Importação Adicionada
import com.azure.cosmos.CosmosException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList; // Importação Adicionada
import java.util.Arrays;
import java.util.Collections; // Importação Adicionada
import java.util.List;
import java.util.UUID;
import java.util.logging.Level; // Importação Adicionada
import java.util.logging.Logger; // Importação Adicionada
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Path("/legoset")
public class LegoSetResource {
    
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();
    private final RedisLayer redis = RedisLayer.getInstance();

    // Adicionar um logger para vermos os erros
    private static final Logger LOGGER = Logger.getLogger(LegoSetResource.class.getName());

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
            LOGGER.info("Invalidando cache para legosets:list (createLegoSet)");
            redis.deleteKey("legosets:list");
            // -----------------------------

            return Response.created(URI.create("/legoset/" + result.getId()))
                    .entity(result.toLegoSet())
                    .build();
        } catch (CosmosException e) {
            if (e.getStatusCode() == 409) {
                return Response.status(Response.Status.CONFLICT).entity("LegoSet already exists.").build();
            }
            LOGGER.log(Level.SEVERE, "Erro CosmosDB ao criar LegoSet", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erro genérico ao criar LegoSet", e);
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
            LOGGER.info("A processar listLegoSets para userId: " + userId);
            return StreamSupport.stream(db.listLegoSetsOfUser(userId).spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }

        // Caminho 2: Recentes (Sem cache neste exemplo)
        if(recent != null) {
            LOGGER.info("A processar listLegoSets para recent: " + st + ", " + len);
            return StreamSupport.stream(db.listMostRecentLegoSets(st, len).spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }

        // --- CACHE-ASIDE: Listar Todos ---
        LOGGER.info("A processar listLegoSets (todos)");
        String cacheKey = "legosets:list";

        // 1. Tentar obter da cache
        List<LegoSet> cachedSets = Collections.emptyList();
        try {
             cachedSets = redis.getLegosetsList();
        } catch (Exception e) {
             LOGGER.log(Level.WARNING, "Falha ao LER legosets:list do Redis", e);
        }
       
        if (cachedSets != null && !cachedSets.isEmpty()) {
            LOGGER.info("Cache HIT para legosets:list");
            return cachedSets;
        }

        // 2. Cache miss: Ir à base de dados
        List<LegoSet> legoSets;
        try {
            LOGGER.info("Cache MISS para legosets:list. A consultar Cosmos DB.");
            legoSets = StreamSupport.stream(db.listLegoSets().spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha ao consultar/mapear legosets da Cosmos DB", e);
            legoSets = Collections.emptyList();
        }
        
        // 3. Guardar na cache
        try {
            LOGGER.info("A guardar legosets:list na cache.");
            redis.setLegosetsList(legoSets);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao GUARDAR legosets:list no Redis", e);
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
            LOGGER.info("Invalidando cache para legosets:list (updateLegoSet)");
            redis.deleteKey("legosets:list");
            // -----------------------------

            return Response.ok().build();
        } catch (Exception e) {
             LOGGER.log(Level.SEVERE, "Falha ao atualizar legoset", e);
             return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteLegoSet(@PathParam("id") String id) {
        try {
            db.deleteLegoSet(id);

            // --- INVALIDAÇÃO DA CACHE ---
            LOGGER.info("Invalidando cache para legosets:list (deleteLegoSet)");
            redis.deleteKey("legosets:list");
            // Também apaga a cache de comentários desse lego (se existir)
            LOGGER.info("Invalidando cache para comments:lego:" + id);
            redis.deleteCommentsForLego(id);
            // -----------------------------

            return Response.noContent().build();
        } catch (Exception e) {
             LOGGER.log(Level.SEVERE, "Falha ao apagar legoset", e);
             return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/{id}/comment")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createComment(@PathParam("id") String legoSetId, Comment comment) {
        if (comment.getCommentText() == null || comment.getCommentText().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Comment text cannot be empty.").build();
        }
        comment.setLegoSetId(legoSetId);
        if (comment.getId() == null || comment.getId().isEmpty()) {
            comment.setId(UUID.randomUUID().toString());
        }
        
        try {
            db.createComment(new CommentDAO(comment));
            
            // --- INVALIDAÇÃO DA CACHE ---
            LOGGER.info("Invalidando cache para comments:lego:" + legoSetId);
            redis.deleteCommentsForLego(legoSetId);
            // -----------------------------

            return Response.status(Response.Status.CREATED).entity(comment).build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erro ao criar comentário", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/{id}/comment")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Comment> listComments(@PathParam("id") String legoSetId) {
        
        LOGGER.info("A processar listComments para: " + legoSetId);
        List<Comment> comments;
        String cacheKey = "comments:lego:" + legoSetId;

        // 1. Tentar obter da cache
        try {
            // A sua RedisLayer.getCommentsForLego devolve lista vazia em vez de null
            // Precisamos de uma verificação mais explícita se a chave existe
            if (redis.existsKey(cacheKey)) {
                LOGGER.info("Cache HIT para: " + cacheKey);
                return redis.getCommentsForLego(legoSetId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao LER do Redis, a continuar para DB", e);
        }

        // 2. Cache MISS: Ir à base de dados
        LOGGER.info("Cache MISS para: " + cacheKey + ". A consultar Cosmos DB.");
        try {
            comments = StreamSupport.stream(db.listCommentsByLegoSet(legoSetId).spliterator(), false)
                    .map(CommentDAO::toComment) // A excepção estava provavelmente aqui
                    .collect(Collectors.toList());
            LOGGER.info("Consulta à DB retornou " + comments.size() + " comentários.");

        } catch (Exception e) {
            // Se a DB falhar (ex: NullPointerException no .map), logamos o erro
            // e devolvemos uma lista vazia para não bloquear o cliente.
            LOGGER.log(Level.SEVERE, "Falha ao consultar/mapear comentários da Cosmos DB", e);
            comments = Collections.emptyList(); // Devolve vazio em vez de crashar
        }

        // 3. Guardar na cache (mesmo que esteja vazia, para cachear a "não existência")
        try {
            LOGGER.info("A guardar na cache para: " + cacheKey);
            redis.setCommentsForLego(legoSetId, comments);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao GUARDAR no Redis", e);
        }

        return comments;
    }
}