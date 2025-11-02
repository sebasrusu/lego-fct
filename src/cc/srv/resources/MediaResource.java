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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/media")
public class MediaResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaResource.class);

    private volatile BlobContainerClient containerClient;
    private final Object lock = new Object();
    private volatile boolean localMode = false;
    private java.nio.file.Path localDir;

    private static final boolean FORCE_AZURE_BLOB =
        "1".equals(System.getenv("FORCE_AZURE_BLOB"));

    public MediaResource() {
    }

    private void ensureContainerClient() {
        if (containerClient == null && !localMode) {
            synchronized (lock) {
                if (containerClient == null && !localMode) {
                    String connectionString = System.getenv("BlobStoreConnection");
                    String containerName = System.getenv().getOrDefault("AZURE_STORAGE_CONTAINER", "media");
                    if (connectionString == null || connectionString.trim().isEmpty()) {
                        if (FORCE_AZURE_BLOB) {
                            LOG.error("FORCE_AZURE_BLOB=1 but BlobStoreConnection is missing");
                            throw new WebApplicationException("BlobStoreConnection missing and FORCE_AZURE_BLOB set", Response.Status.INTERNAL_SERVER_ERROR);
                        }
                        try {
                            localMode = true;
                            localDir = Paths.get(System.getProperty("user.dir"), "media-store");
                            Files.createDirectories(localDir);
                            LOG.info("BlobStoreConnection missing — using local media dir: {}", localDir.toString());
                            return;
                        } catch (IOException ioe) {
                            LOG.error("Failed to create local media dir", ioe);
                            throw new WebApplicationException("Server misconfigured: missing BlobStoreConnection and cannot create local storage", Response.Status.INTERNAL_SERVER_ERROR);
                        }
                    }
                    try {
                        var svc = new BlobServiceClientBuilder()
                                .connectionString(connectionString)
                                .buildClient();
                        var client = svc.getBlobContainerClient(containerName);
                        try {
                            if (!client.exists()) client.create();
                        } catch (Exception e) {
                            LOG.warn("Could not create/verify container '{}' : {}", containerName, e.getMessage());
                        }
                        containerClient = client;
                    } catch (IllegalArgumentException iae) {
                        LOG.error("Invalid Azure Blob connection string", iae);
                        throw new WebApplicationException("Invalid BlobStoreConnection", Response.Status.INTERNAL_SERVER_ERROR);
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
        long elapsed;
        if (localMode) {
            try {
                java.nio.file.Path f = localDir.resolve(id);
                Files.write(f, contents, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                // optionally store contentType as metadata file
                if (contentType != null && !contentType.isBlank()) {
                    Files.writeString(localDir.resolve(id + ".ct"), contentType, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                elapsed = Duration.between(start, Instant.now()).toMillis();
                LOG.info("POST /rest/media stored locally in {} ms (id={})", elapsed, id);
                return Response.ok("\"" + id + "\"").header("X-Backend-Time-ms", String.valueOf(elapsed)).build();
            } catch (IOException e) {
                LOG.error("Failed to write local media file", e);
                throw new WebApplicationException("Local storage error", Response.Status.INTERNAL_SERVER_ERROR);
            }
        }
        // Azure blob path
        BlobClient blob = containerClient.getBlobClient(id);
        String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
        BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(ct);
        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(contents);
        try {
            blob.uploadWithResponse(inputStream, contents.length, null, headers, null, null, null, java.time.Duration.ofMinutes(1), Context.NONE);
            elapsed = Duration.between(start, Instant.now()).toMillis();
            LOG.info("POST /rest/media uploaded to blob in {} ms (id={})", elapsed, id);
            return Response.ok("\"" + id + "\"").header("X-Backend-Time-ms", String.valueOf(elapsed)).build();
        } catch (Exception e) {
            LOG.error("Failed to upload blob", e);
            throw new WebApplicationException("Blob upload error", Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            try { inputStream.close(); } catch (IOException ex) { LOG.debug("Failed to close upload input stream", ex); }
        }
    }

    @GET
    @Path("/{id}")
    @Produces({ "image/*", "video/*" })
    public Response download(@PathParam("id") String id) {
        ensureContainerClient();
        if (localMode) {
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                java.nio.file.Path f = localDir.resolve(id);
                if (!Files.exists(f)) return Response.status(Response.Status.NOT_FOUND).build();
                byte[] data = Files.readAllBytes(f);
                String ct = null;
                java.nio.file.Path ctFile = localDir.resolve(id + ".ct");
                if (Files.exists(ctFile)) {
                    ct = Files.readString(ctFile);
                } else {
                    ct = Files.probeContentType(f);
                }
                if (ct == null) ct = MediaType.APPLICATION_OCTET_STREAM;
                return Response.ok(data, ct).build();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Local download failed for id {0}: {1}", new Object[]{id, e.getMessage()});
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        } else {
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                var blobClient = containerClient.getBlobClient(id);
                blobClient.downloadStream(os);
                String contentType = blobClient.getProperties().getContentType();
                return Response.ok(os.toByteArray(), contentType).build();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Download failed for id {0}: {1}", new Object[]{id, e.getMessage()});
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> list() {
        ensureContainerClient();
        if (localMode) {
            try {
                List<String> names = new ArrayList<>();
                try (DirectoryStream<java.nio.file.Path> ds = Files.newDirectoryStream(localDir, entry -> !entry.getFileName().toString().endsWith(".ct"))) {
                    for (java.nio.file.Path p : ds) names.add(p.getFileName().toString());
                }
                return names;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to list local media", e);
                return List.of();
            }
        } else {
            return containerClient.listBlobs().stream()
                    .map(blob -> blob.getName())
                    .collect(Collectors.toList());
        }
    }

    public Response uploadMedia() {
        Instant start = Instant.now();
        try {
            Response resp = doActualUpload();
            return resp;
        } finally {
            long ms = Duration.between(start, Instant.now()).toMillis();
            LOG.info("POST /rest/media handled in {} ms", ms);
        }
    }
}
