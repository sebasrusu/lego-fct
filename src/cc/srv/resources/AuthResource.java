package cc.srv.resources;

import cc.data.auth.Login;
import cc.data.auth.Session;
import cc.data.user.UserDAO;
import cc.db.CosmosDBLayer;
import cc.db.RedisLayer;
import cc.utils.Hash;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.util.UUID;

@Path("/user")
public class AuthResource {
    private final CosmosDBLayer db = CosmosDBLayer.getInstance();

    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response auth(Login login) {
        if (login == null || login.getUser() == null || login.getPwd() == null)
            throw new NotAuthorizedException("Credenciais em falta");

        UserDAO dao = db.findUserByNickname(login.getUser());
        boolean ok = dao != null && dao.getPwd() != null
                && dao.getPwd().equals(Hash.of(login.getPwd()));

        if (!ok) throw new NotAuthorizedException("Login incorreto");

        String sid = UUID.randomUUID().toString();
        NewCookie cookie;
        try {
            cookie = new NewCookie.Builder("scc:session")
                    .value(sid).path("/")
                    .comment("sessionid")
                    .maxAge(3600)
                    .secure(true)       // em produção: true (HTTPS). Em dev local podes pôr false.
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build();
        } catch (NoSuchMethodError e) {
            cookie = new NewCookie("scc:session", sid, "/", null, "sessionid", 3600, true, true);
        }

        RedisLayer.getInstance().putSession(new Session(sid, dao.getId(), System.currentTimeMillis()));
        return Response.ok().cookie(cookie).build();
    }

    @POST
    @Path("/logout")
    public Response logout(@CookieParam("scc:session") Cookie c) {
        if (c != null) {
            RedisLayer.getInstance().deleteSession(c.getValue());
            NewCookie gone = new NewCookie("scc:session", "", "/", null, "sessionid", 0, true, true);
            return Response.noContent().cookie(gone).build();
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response me(@CookieParam("scc:session") Cookie c) {
        if (c == null) throw new NotAuthorizedException("Sem sessão");
        var sess = RedisLayer.getInstance().getSession(c.getValue());
        if (sess == null) throw new NotAuthorizedException("Sessão inválida/expirada");
        var dao = db.getUser(sess.getUserId());
        if (dao == null) throw new NotAuthorizedException("Utilizador não encontrado");
        return Response.ok(dao.toUser()).build(); // pwd não sai por ser WRITE_ONLY
    }
}
