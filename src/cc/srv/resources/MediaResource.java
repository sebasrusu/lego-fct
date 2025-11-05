package cc.srv.resources;

import cc.utils.Hash;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.core.util.Context;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.BlobClient;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/media")
public class MediaResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaResource.class);

    private volatile BlobContainerClient containerClient;
    private final Object lock = new Object();

    public MediaResource() { }

    private void ensureContainerClient() {
        if (containerClient == null) {
            synchronized (lock) {
                if (containerClient == null) {
                    String connectionString = System.getenv("BlobStoreConnection");
                    if (connectionString == null || connectionString.trim().isEmpty()) {
                        // try azurekeys.props as fallback only for server envs where .env not used
                        try {
                            var props = cc.utils.AzureProperties.getProperties();
                            connectionString = props.getProperty(cc.utils.AzureProperties.BLOB_KEY);
                        } catch (Exception ignored) {}
                    }
                    if (connectionString == null || connectionString.trim().isEmpty()) {
                        LOG.error("BlobStoreConnection missing (no .env / azurekeys.props).");
                        throw new WebApplicationException("BlobStoreConnection missing", Response.Status.INTERNAL_SERVER_ERROR);
                    }

                    String containerName = System.getenv().getOrDefault("AZURE_STORAGE_CONTAINER", "media");
                    try {
                        var svc = new BlobServiceClientBuilder()
                                .connectionString(connectionString)
                                .buildClient();
                        var client = svc.getBlobContainerClient(containerName);
                        // do NOT create container; require it to exist
                        if (!client.exists()) {
                            LOG.error("Blob container '{}' does not exist in storage account.", containerName);
                            throw new WebApplicationException("Blob container not found: " + containerName, Response.Status.INTERNAL_SERVER_ERROR);
                        }
                        containerClient = client;
                        LOG.info("Connected to Azure Blob container '{}'", containerName);
                    } catch (IllegalArgumentException iae) {
                        LOG.error("Invalid Azure Blob connection string", iae);
                        throw new WebApplicationException("Invalid BlobStoreConnection", Response.Status.INTERNAL_SERVER_ERROR);
                    } catch (WebApplicationException wae) {
                        throw wae;
                    } catch (Exception e) {
                        LOG.error("Failed to initialize Blob container client", e);
                        throw new WebApplicationException("Blob init error", Response.Status.INTERNAL_SERVER_ERROR);
                    }
                }
            }
        }
    }

    @POST
    @Consumes({ "image/*", "video/*" })
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@HeaderParam("Content-Type") String contentType, byte[] contents) {
        Instant start = Instant.now();
        ensureContainerClient();
        String id = Hash.of(contents);
        BlobClient blob = containerClient.getBlobClient(id);
        String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
        BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(ct);
        try (java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(contents)) {
            // overwrite existing blob
            blob.uploadWithResponse(inputStream, contents.length, null, headers, null, null, null, java.time.Duration.ofMinutes(1), Context.NONE);
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            LOG.info("POST /rest/media uploaded to blob in {} ms (id={})", elapsed, id);
            return Response.ok("\"" + id + "\"").header("X-Backend-Time-ms", String.valueOf(elapsed)).build();
        } catch (Exception e) {
            LOG.error("Failed to upload blob", e);
            throw new WebApplicationException("Blob upload error", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/{id}")
    @Produces({ "image/*", "video/*" })
    public Response download(@PathParam("id") String id) {
        ensureContainerClient();
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            var blobClient = containerClient.getBlobClient(id);
            if (!blobClient.exists()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            blobClient.downloadStream(os);
            String contentType = blobClient.getProperties().getContentType();
            if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM;
            return Response.ok(os.toByteArray(), contentType).build();
        } catch (Exception e) {
            LOG.debug("Download failed for id {}: {}", id, e.getMessage(), e);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> list() {
        ensureContainerClient();
        return containerClient.listBlobs().stream()
                .map(blob -> blob.getName())
                .collect(Collectors.toList());
    }
}
