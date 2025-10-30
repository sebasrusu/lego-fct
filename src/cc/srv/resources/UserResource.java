package cc.srv.resources;

import cc.data.user.User;
import cc.data.user.UserDAO;
import cc.db.CosmosDBLayer;
import cc.utils.Hash;
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
    @Produces(MediaType.APPLICATION_JSON)
    public Response createUser(User user) {
        if (user.getNickname() == null || user.getNickname().isBlank() || user.getPwd() == null || user.getPwd().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Nickname e password são obrigatórios.").build();
        }

        if (db.findUserByNickname(user.getNickname()) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Nickname já existe.").build();
        }

        if (user.getId() == null || user.getId().isBlank())
            user.setId(UUID.randomUUID().toString());

        user.setPwd(Hash.of(user.getPwd()));

        UserDAO created = db.createUser(new UserDAO(user));

        return Response.created(URI.create("/user/" + created.getId()))
                .entity(created.toUser())
                .build();
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
                .map(UserDAO::toUser)
                .collect(Collectors.toList());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateUser(@PathParam("id") String id, User user) {
        if (!id.equals(user.getId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("\"ID do path e do body não correspondem.\"").build();
        }

        UserDAO existing = db.getUser(id);
        if (existing == null)
            return Response.status(Response.Status.NOT_FOUND).build();

        // mantém hash antigo se a pwd não for alterada
        String newPwdHash = existing.getPwd();
        if (user.getPwd() != null && !user.getPwd().isBlank()) {
            newPwdHash = Hash.of(user.getPwd());
        }

        UserDAO toUpdate = new UserDAO(new User(
                id,
                user.getName() != null ? user.getName() : existing.getName(),
                user.getNickname() != null ? user.getNickname() : existing.getNickname(),
                newPwdHash,
                user.getPhotoId() != null ? user.getPhotoId() : existing.getPhotoId(),
                user.getLegoIds() != null ? user.getLegoIds() : existing.getLegoIds()
        ));

        db.updateUser(toUpdate);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") String id) {
        UserDAO u = db.getUser(id);
        if (u == null)
            return Response.status(Response.Status.NOT_FOUND).build();

        //u.setName("");
        //u.setNickname("Deleted Nickname");
        //u.setPwd("");
        //u.setPhotoId(null);
        //u.setLegoIds(new String[0]);
        //db.deleteUser(u);

        db.deleteUser(id);
        return Response.noContent().build();
    }

}