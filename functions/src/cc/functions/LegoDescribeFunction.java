package cc.functions;

import cc.db.CosmosDBLayer;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.util.logging.Logger;
import java.net.http.*;
import java.net.URI;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import java.util.Map;

public class LegoDescribeFunction {
    private static final Logger LOG = Logger.getLogger(LegoDescribeFunction.class.getName());

    @FunctionName("LegoDescribeOnBlob")
    public void run(
        @BlobTrigger(name = "content", path = "media/{name}", connection = "BLOB_STORAGE_CONN") byte[] content,
        @BindingName("name") String fileName,
        final ExecutionContext context) {

        LOG.info("Blob trigger: " + fileName);
        try {
            // tenta obter legoSetId a partir da metadata do blob (se o uploader a tiver definido)
            String legoSetId = null;
            try {
                String conn = System.getenv("BLOB_STORAGE_CONN");
                if (conn != null && !conn.isEmpty()) {
                    BlobContainerClient container = new BlobServiceClientBuilder().connectionString(conn).buildClient().getBlobContainerClient("media");
                    BlobClient blob = container.getBlobClient(fileName);
                    if (blob.exists()) {
                        BlobProperties props = blob.getProperties();
                        Map<String, String> meta = props.getMetadata();
                        if (meta != null && meta.containsKey("legoSetId")) {
                            legoSetId = meta.get("legoSetId");
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.fine("Could not read blob metadata: " + ex.getMessage());
            }
            if (legoSetId == null) {
                // se o nome seguir <id>-..., usa a parte antes do '-'; caso contrário usa o próprio nome do blob
                legoSetId = fileName.contains("-") ? fileName.split("-")[0] : fileName;
            }

            String cvEndpoint = System.getenv("AZURE_CV_ENDPOINT");
            String cvKey = System.getenv("AZURE_CV_KEY");
            if (cvEndpoint == null || cvKey == null) {
                LOG.severe("AZURE_CV_ENDPOINT ou AZURE_CV_KEY não estão definidas");
                return;
            }

            HttpClient http = HttpClient.newHttpClient();
            URI uri = URI.create(cvEndpoint + "/vision/v3.2/describe");
            HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Ocp-Apim-Subscription-Key", cvKey)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.severe("Computer Vision API returned status " + resp.statusCode() + " body=" + resp.body());
                return;
            }

            String description = "no-description";
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(resp.body());
                if (node.has("description") && node.get("description").has("captions") && node.get("description").get("captions").isArray()) {
                    var caps = node.get("description").get("captions");
                    if (caps.size() > 0) description = caps.get(0).get("text").asText();
                }
            } catch (Exception ex) {
                LOG.warning("Failed to parse CV response: " + ex.getMessage());
            }

            CosmosDBLayer db = CosmosDBLayer.getInstance();
            db.upsertLegoDescription(legoSetId, description, new String[0], "vision");
            LOG.info("Saved description for " + legoSetId);
        } catch (Exception e) {
            LOG.severe("LegoDescribe error: " + e.getMessage());
        }
    }
}