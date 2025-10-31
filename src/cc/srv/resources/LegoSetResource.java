package cc.srv.resources;

import cc.data.comment.Comment;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSet;
import cc.data.lego.LegoSetDAO;
import cc.db.CosmosDBLayer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Path("/legoset")
public class LegoSetResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createLegoSet(LegoSet legoSet) {
        if (legoSet.getId() == null || legoSet.getId().isEmpty()) {
            legoSet.setId(UUID.randomUUID().toString());
        }
        LegoSetDAO result = db.createLegoSet(new LegoSetDAO(legoSet));
        return Response.created(URI.create("/legoset/" + result.getId()))
                .entity(result.toLegoSet())
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLegoSet(@PathParam("id") String id) {
        LegoSetDAO dao = db.getLegoSet(id);
        return dao != null ? Response.ok(dao.toLegoSet()).build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<LegoSet> listLegoSets(@QueryParam("userId") String userId, @QueryParam("recent") boolean recent) {
        if (userId != null && !userId.isEmpty()) {
            return StreamSupport.stream(db.listLegoSetsOfUser(userId).spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }
        if(recent) {
            return StreamSupport.stream(db.listMostRecentLegoSets().spliterator(), false)
                    .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
        }
        return StreamSupport.stream(db.listLegoSets().spliterator(), false)
                .map(LegoSetDAO::toLegoSet).collect(Collectors.toList());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateLegoSet(@PathParam("id") String id, LegoSet legoSet) {
        if (!id.equals(legoSet.getId())) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        db.updateLegoSet(new LegoSetDAO(legoSet));
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteLegoSet(@PathParam("id") String id) {
        db.deleteLegoSet(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/comment")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createComment(@PathParam("id") String legoSetId, Comment comment) {
        comment.setLegoSetId(legoSetId);
        if (comment.getId() == null || comment.getId().isEmpty()) {
            comment.setId(UUID.randomUUID().toString());
        }
        db.createComment(new CommentDAO(comment));
        return Response.status(Response.Status.CREATED).build();
    }

    @GET
    @Path("/{id}/comment")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Comment> listComments(@PathParam("id") String legoSetId) {
        return StreamSupport.stream(db.listCommentsByLegoSet(legoSetId).spliterator(), false)
                .map(CommentDAO::toComment).collect(Collectors.toList());
    }
}
