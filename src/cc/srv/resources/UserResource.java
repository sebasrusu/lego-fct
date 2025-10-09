package cc.srv.resources;

import cc.data.user.User;
import cc.data.user.UserDAO;
import cc.db.CosmosDBLayer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Path("/user")
public class UserResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createUser(User user) {
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setId(UUID.randomUUID().toString());
        }
        UserDAO result = db.createUser(new UserDAO(user));
        return Response.created(URI.create("/user/" + result.getId())).build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUser(@PathParam("id") String id) {
        UserDAO dao = db.getUser(id);
        if (dao == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dao.toUser()).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<User> listUsers() {
        return StreamSupport.stream(db.listUsers().spliterator(), false)
                .map(UserDAO::toUser).collect(Collectors.toList());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateUser(@PathParam("id") String id, User user) {
        if (!id.equals(user.getId())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("\"ID do path e do body não correspondem.\"").build();
        }
        db.updateUser(new UserDAO(user));
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") String id) {
        UserDAO user = db.getUser(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        user.setName("Deleted User");
        user.setPwd("");
        user.setPhotoId(null);
        user.setLegoIds(new String[0]);
        db.updateUser(user);

        return Response.noContent().build();
    }
}