package cc.srv.resources;

import cc.db.CosmosDBLayer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/media-analysis")
public class MediaAnalysisResource {
    private static final Logger LOG = LoggerFactory.getLogger(MediaAnalysisResource.class);

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysis(@PathParam("id") String id) {
        LOG.info("MediaAnalysisResource.getAnalysis called id={}", id);
        try {
            Map<String, Object> doc = CosmosDBLayer.getInstance().getLegoDescription(id);
            LOG.info("getLegoDescription returned for id={} -> {}", id, (doc == null ? "null" : "present"));
            if (doc == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No analysis found for id: " + id))
                        .build();
            }
            return Response.ok(doc).build();
        } catch (Exception e) {
            LOG.error("getAnalysis error id={} err={}", id, e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "internal error", "detail", e.getMessage()))
                    .build();
        }
    }
}