package cc.srv.resources;

import cc.utils.Hash;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;

@Path("/media")
public class MediaResource {

	private final BlobContainerClient containerClient;

	public MediaResource() {
		String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
		containerClient = new BlobServiceClientBuilder()
				.connectionString(connectionString)
				.buildClient()
				.getBlobContainerClient("media");
	}

	@POST
	@Consumes("image/*,video/*")
	@Produces(MediaType.APPLICATION_JSON)
	public Response upload(@HeaderParam("Content-Type") String contentType, byte[] contents) {
		String id = Hash.of(contents);
		containerClient.getBlobClient(id).upload(new ByteArrayInputStream(contents), contents.length, true);
		return Response.ok("\"" + id + "\"").build();
	}

	@GET
	@Path("/{id}")
	@Produces("image/*,video/*")
	public Response download(@PathParam("id") String id) {
		try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
			containerClient.getBlobClient(id).downloadStream(os);
			return Response.ok(os.toByteArray()).build();
		} catch (Exception e) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public List<String> list() {
		return containerClient.listBlobs().stream()
				.map(blob -> blob.getName())
				.collect(Collectors.toList());
	}
}