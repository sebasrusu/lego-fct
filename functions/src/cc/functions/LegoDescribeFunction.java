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
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LegoDescribeFunction {
    private static final Logger LOG = Logger.getLogger(LegoDescribeFunction.class.getName());

    @FunctionName("LegoDescribeOnBlob")
    public void run(
        @BlobTrigger(name = "content", path = "media/{name}", connection = "BLOB_STORAGE_CONN", dataType = "binary") byte[] content,
        @BindingName("name") String fileName,
        final ExecutionContext context) {

        LOG.info("Blob trigger: " + fileName);
        try {
            // get legoSetId from blob metadata (if present)
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
                legoSetId = fileName.contains("-") ? fileName.split("-")[0] : fileName;
            }

            String cvEndpoint = System.getenv("AZURE_CV_ENDPOINT");
            String cvKey = System.getenv("AZURE_CV_KEY");
            if (cvEndpoint == null || cvKey == null) {
                LOG.severe("AZURE_CV_ENDPOINT or AZURE_CV_KEY not defined");
                return;
            }

            // Use the Analyze endpoint to get objects, tags, colors and description (richer output than describe)
            HttpClient http = HttpClient.newHttpClient();
            String analyzeUrl = cvEndpoint;
            if (!analyzeUrl.endsWith("/")) analyzeUrl += "/";
            analyzeUrl += "vision/v3.2/analyze?visualFeatures=Objects,Description,Color,Tags";
            HttpRequest req = HttpRequest.newBuilder(URI.create(analyzeUrl))
                .header("Ocp-Apim-Subscription-Key", cvKey)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.severe("Computer Vision API returned status " + resp.statusCode() + " body=" + resp.body());
                return;
            }

            // parse analyze response: description, tags, colors, objects
            String description = "";
            List<String> cvTags = new ArrayList<>();
            Map<String,Integer> objectCounts = new HashMap<>();
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(resp.body());

                if (root.has("description") && root.get("description").has("captions") && root.get("description").get("captions").isArray()) {
                    var caps = root.get("description").get("captions");
                    if (caps.size() > 0) description = caps.get(0).get("text").asText();
                }
                // tags
                if (root.has("tags") && root.get("tags").isArray()) {
                    for (var t : root.get("tags")) {
                        if (t.has("name")) cvTags.add(t.get("name").asText().toLowerCase());
                        else if (t.isTextual()) cvTags.add(t.asText().toLowerCase());
                    }
                }
                // colors
                if (root.has("color") && root.get("color").has("dominantColors")) {
                    for (var c : root.get("color").get("dominantColors")) {
                        if (c.isTextual()) cvTags.add(c.asText().toLowerCase());
                    }
                }
                // objects (count occurrences)
                if (root.has("objects") && root.get("objects").isArray()) {
                    for (var o : root.get("objects")) {
                        String objName = null;
                        if (o.has("object")) objName = o.get("object").asText().toLowerCase();
                        else if (o.has("name")) objName = o.get("name").asText().toLowerCase();
                        if (objName == null || objName.isBlank()) continue;
                        objectCounts.put(objName, objectCounts.getOrDefault(objName, 0) + 1);
                    }
                }
            } catch (Exception ex) {
                LOG.warning("Failed to parse CV analyze response: " + ex.getMessage());
            }

            // normalize description and extract tags using richer CV output
            Map<String,Object> normalized = normalizeLegoDescription(description, cvTags, objectCounts, fileName);
            String normalizedDescription = (String) normalized.get("description");
            String[] normalizedTags = ((List<String>) normalized.get("tags")).toArray(new String[0]);

            CosmosDBLayer db = CosmosDBLayer.getInstance();
            db.upsertLegoDescription(legoSetId, normalizedDescription, normalizedTags, "vision");
            LOG.info("Saved description for " + legoSetId);
        } catch (Exception e) {
            LOG.severe("LegoDescribe error: " + e.getMessage());
        }
    }

    // helper methods
    private Map<String,Object> normalizeLegoDescription(String desc, List<String> cvTags, Map<String,Integer> objectCounts, String fileName) {
        Map<String,Object> out = new HashMap<>();
        if (desc == null) desc = "";
        String low = desc.toLowerCase();

        // count detected objects
        int totalObjects = 0;
        if (objectCounts != null) {
            for (Integer v : objectCounts.values()) totalObjects += v;
        }

        // detect minifigs/persons
        int minifigCount = -1;
        if (objectCounts != null) {
            for (var e : objectCounts.entrySet()) {
                String k = e.getKey().toLowerCase();
                if (k.contains("minifig") || k.contains("minifigure") || k.contains("figure") || k.contains("person") || k.contains("people")) {
                    minifigCount = Math.max(minifigCount, e.getValue());
                }
            }
        }
        if (minifigCount <= 0) minifigCount = extractCount(low);

        // detect categories by keywords
        String[] vehicleKeywords = {"ship","spaceship","vehicle","car","truck","plane","x-wing","falcon","millennium","tie","fighter","speeder"};
        String[] animalKeywords = {"dog","cat","horse","dragon","bird","elephant","lion","tiger","wolf","wookiee","chewbacca"};
        boolean hasVehicle = false, hasAnimal = false;
        Set<String> objKeys = objectCounts != null ? objectCounts.keySet() : new LinkedHashSet<>();
        for (String k : objKeys) {
            String kk = k.toLowerCase();
            for (String v : vehicleKeywords) if (kk.contains(v)) hasVehicle = true;
            for (String a : animalKeywords) if (kk.contains(a)) hasAnimal = true;
        }
        // also inspect cvTags/description
        if (cvTags != null) {
            for (String t : cvTags) {
                String tt = t.toLowerCase();
                for (String v : vehicleKeywords) if (tt.contains(v)) hasVehicle = true;
                for (String a : animalKeywords) if (tt.contains(a)) hasAnimal = true;
            }
        }
        for (String v : vehicleKeywords) if (low.contains(v)) hasVehicle = true;
        for (String a : animalKeywords) if (low.contains(a)) hasAnimal = true;

        // colors: top 2 from cvTags + desc
        String[] colors = {"red","blue","green","yellow","black","white","orange","purple","brown","gray","grey","pink","silver","gold"};
        Map<String,Integer> colorCounts = new HashMap<>();
        if (cvTags != null) for (String t : cvTags) for (String c : colors) if (t.toLowerCase().contains(c)) colorCounts.put(c, colorCounts.getOrDefault(c,0)+1);
        for (String c : colors) if (low.contains(c)) colorCounts.put(c, colorCounts.getOrDefault(c,0)+1);
        List<String> topColors = new ArrayList<>();
        if (!colorCounts.isEmpty()) {
            List<Map.Entry<String,Integer>> cols = new ArrayList<>(colorCounts.entrySet());
            cols.sort((a,b)->b.getValue().compareTo(a.getValue()));
            for (int i=0;i<Math.min(2, cols.size()); i++) topColors.add(cols.get(i).getKey());
        }

        // decide category
        String category;
        if (hasVehicle) category = "Vehicle";
        else if (hasAnimal) category = "Animal";
        else if (minifigCount > 0 && totalObjects <= 1) category = "Figure";
        else if (totalObjects > 1) category = "Set";
        else category = "Unknown";

        // ensure sensible counts
        int elementsCount = totalObjects > 0 ? totalObjects : (minifigCount > 0 ? 1 : -1);

        // build concise description
        StringBuilder sb = new StringBuilder();
        sb.append(category);
        if (elementsCount > 0) sb.append(String.format(" - %d element%s", elementsCount, elementsCount>1 ? "s" : ""));
        if (minifigCount > 0) sb.append(String.format(" - %d minifigure%s", minifigCount, minifigCount>1 ? "s" : ""));
        if (!topColors.isEmpty()) sb.append(" - predominantly ").append(String.join(", ", topColors));

        String finalDesc = sb.toString();
        if (finalDesc.isBlank()) finalDesc = "LEGO item";

        out.put("description", capitalize(finalDesc));

        // minimal tags
        List<String> tags = new ArrayList<>();
        tags.add("lego");
        tags.add(category.toLowerCase());
        if (elementsCount > 0) tags.add("elements-" + elementsCount);
        if (minifigCount > 0) tags.add("minifigs-" + minifigCount);
        for (String c : topColors) tags.add(c);
        out.put("tags", tags);

        return out;
    }

    // add missing helpers used by normalizeLegoDescription
    private int extractCount(String text) {
        if (text == null) return -1;
        // explicit numeric
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        // common words -> numbers
        Map<String,Integer> words = new HashMap<>();
        words.put("one",1); words.put("two",2); words.put("three",3); words.put("four",4); words.put("five",5);
        words.put("six",6); words.put("seven",7); words.put("eight",8); words.put("nine",9); words.put("ten",10);
        words.put("several",3); words.put("many",4); words.put("couple",2);
        String lower = text.toLowerCase();
        for (Map.Entry<String,Integer> e : words.entrySet()) {
            if (lower.contains(" " + e.getKey() + " ") || lower.startsWith(e.getKey() + " ") || lower.endsWith(" " + e.getKey())) {
                return e.getValue();
            }
        }
        return -1;
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        s = s.trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String normalizeTag(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-");
    }
}