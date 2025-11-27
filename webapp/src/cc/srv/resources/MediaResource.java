package cc.srv.resources;

import cc.utils.Hash;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import java.time.Duration;
import java.time.Instant;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@jakarta.ws.rs.Path("/media")
public class MediaResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaResource.class);

    private final Path baseDir;

    public MediaResource() {
        String base = System.getenv("MEDIA_BASE_PATH");
        if (base == null || base.isBlank()) {
            base = "/media";
        }
        this.baseDir = Paths.get(base);
        try {
            Files.createDirectories(baseDir);
            LOG.info("MediaResource initialized with baseDir={}", baseDir.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Failed to create media base directory {}", baseDir, e);
            throw new WebApplicationException("Media directory init error", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Consumes({ "image/*", "video/*" })
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@HeaderParam("Content-Type") String contentType, byte[] contents) {
        Instant start = Instant.now();

        if (contents == null || contents.length == 0) {
            throw new WebApplicationException("Empty media", Response.Status.BAD_REQUEST);
        }

        String id = Hash.of(contents);
        String ct = (contentType != null && !contentType.isBlank())
                ? contentType
                : "application/octet-stream";

        Path dataFile = baseDir.resolve(id);
        Path ctFile   = baseDir.resolve(id + ".ct");

        try (ByteArrayInputStream in = new ByteArrayInputStream(contents)) {
            Files.copy(in, dataFile, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(ctFile, ct, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long elapsed = Duration.between(start, Instant.now()).toMillis();
            LOG.info("POST /rest/media stored on filesystem in {} ms (id={}, path={})",
                    elapsed, id, dataFile.toAbsolutePath());

            return Response.ok("\"" + id + "\"")
                    .header("X-Backend-Time-ms", String.valueOf(elapsed))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to store media file (id={})", id, e);
            throw new WebApplicationException("Media store error", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @jakarta.ws.rs.Path("/{id}")
    @Produces({ "image/*", "video/*" })
    public Response download(@PathParam("id") String id) {
        Path dataFile = baseDir.resolve(id);
        Path ctFile   = baseDir.resolve(id + ".ct");

        try {
            if (!Files.exists(dataFile)) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            byte[] data;
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                Files.copy(dataFile, os);
                data = os.toByteArray();
            }

            String contentType = MediaType.APPLICATION_OCTET_STREAM;

            if (Files.exists(ctFile)) {
                try {
                    String storedCt = Files.readString(ctFile, StandardCharsets.UTF_8).trim();
                    if (!storedCt.isBlank()) {
                        contentType = storedCt;
                    }
                } catch (Exception ignore) {}
            } else {
                try {
                    String probed = Files.probeContentType(dataFile);
                    if (probed != null && !probed.isBlank()) {
                        contentType = probed;
                    }
                } catch (Exception ignore) {}
            }

            return Response.ok(data, contentType).build();
        } catch (FileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (Exception e) {
            LOG.debug("Download failed for id {}: {}", id, e.getMessage(), e);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> list() {
        try (Stream<Path> stream = Files.list(baseDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.endsWith(".ct"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.error("Failed to list media files", e);
            throw new WebApplicationException("Media list error", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }
}
