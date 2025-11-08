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
    private static final String SESSION_COOKIE = "scc:session";
    private static final int SESSION_TTL_SECONDS = 3600;

    private final CosmosDBLayer db = CosmosDBLayer.getInstance();

    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response auth(Login login) {
        // erro 400 quando o body está incompleto
        if (login == null || login.getUser() == null || login.getPwd() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Campos 'user' e 'pwd' são obrigatórios")
                    .build();
        }

        UserDAO dao = db.getUser(login.getUser());
        boolean ok = dao != null && dao.getPwd() != null
                && dao.getPwd().equals(Hash.of(login.getPwd()));

        // erro 401 quando credenciais não batem certo
        if (!ok) throw new NotAuthorizedException("Login incorreto");

        String sid = UUID.randomUUID().toString();

        // criar cookie de sessão
        NewCookie cookie;
        try {
            cookie = new NewCookie.Builder(SESSION_COOKIE)
                    .value(sid)
                    .path("/")
                    .comment("sessionid")
                    .maxAge(SESSION_TTL_SECONDS)
                    .secure(true)               // Azure é HTTPS -> true, local -> false
                    .httpOnly(true)
                    .build();
        } catch (NoSuchMethodError e) {
            // fallback para Resteasy mais antigo
            cookie = new NewCookie(SESSION_COOKIE, sid, "/", null, "sessionid", SESSION_TTL_SECONDS, true, true);
        }

        // guardar sessão no Redis
        RedisLayer.getInstance().putSession(new Session(sid, dao.getId(), System.currentTimeMillis()));

        return Response.ok("Login com sucesso").cookie(cookie).build();
    }

    @POST
    @Path("/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout(@CookieParam(SESSION_COOKIE) Cookie c) {
        if (c != null) {
            RedisLayer.getInstance().deleteSession(c.getValue());
            // invalidar cookie no cliente
            NewCookie gone = new NewCookie(SESSION_COOKIE, "", "/", null, "sessionid", 0, true, true);
            return Response.ok("Logout efetuado").cookie(gone).build();
        }
        return Response.ok("Sem sessão ativa").build();
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response me(@CookieParam(SESSION_COOKIE) Cookie c) {
        if (c == null) throw new NotAuthorizedException("Sem sessão");
        var sess = RedisLayer.getInstance().getSession(c.getValue());
        if (sess == null) throw new NotAuthorizedException("Sessão inválida/expirada");
        var dao = db.getUser(sess.getUserId());
        if (dao == null) throw new NotAuthorizedException("Utilizador não encontrado");
        return Response.ok(dao.toUser()).build();
    }
}
