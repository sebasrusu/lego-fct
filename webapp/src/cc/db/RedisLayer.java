package cc.db;

import cc.data.auth.Session;
import cc.data.comment.Comment;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSet;
import cc.data.lego.LegoSetDAO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azure.cosmos.util.CosmosPagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RedisLayer {
    private static volatile RedisLayer INSTANCE;
    public static RedisLayer getInstance() {
        if (INSTANCE == null) {
            synchronized (RedisLayer.class) {
                if (INSTANCE == null) INSTANCE = new RedisLayer();
            }
        }
        return INSTANCE;
    }

    private JedisPooled jedis = null;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int ttlSeconds = 3600; // 1h

    private final String REDIS_URL;
    private final String REDIS_KEY;
    private volatile boolean available = false;

    private static final Logger logger = LoggerFactory.getLogger(RedisLayer.class);

    private RedisLayer() {
        String url = System.getenv("REDIS_URL");
        String key = System.getenv("REDIS_KEY");
        REDIS_URL = url;
        REDIS_KEY = key;
        initClient();
    }

    private void initClient() {
        if (REDIS_URL == null || REDIS_URL.isBlank()) {
            available = false;
            logger.info("Redis URL not configured, Redis disabled");
            return;
        }
        try {
            String host = REDIS_URL;
            int port = 6379;
            if (REDIS_URL.contains(":")) {
                String[] parts = REDIS_URL.split(":", 2);
                host = parts[0];
                try { port = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
            }
            // Simple connection; extend for auth/TLS if needed for Azure.
            jedis = new JedisPooled(host, port);
            available = true;
            logger.info("Redis client initialized {}", host + ":" + port);
        } catch (Throwable t) {
            available = false;
            logger.error("Failed to init Redis client", t);
        }
    }

    public boolean isAvailable() { return available; }

    public void putSession(Session s) {
        if (!available || jedis == null || s == null || s.getSid() == null) return;
        try {
            String k = key(s.getSid());
            String json = mapper.writeValueAsString(s);
            jedis.setex(k, ttlSeconds, json);
        } catch (Exception e) {
            logger.error("Redis putSession EX", e);
        }
    }

    public Session getSession(String sid) {
        if (!available || jedis == null || sid == null) return null;
        try {
            String k = key(sid);
            String json = jedis.get(k);
            return json == null ? null : mapper.readValue(json, Session.class);
        } catch (Exception e) {
            logger.error("Redis getSession EX", e);
            return null;
        }
    }

    public void deleteSession(String sid) {
        if (!available || jedis == null || sid == null) return;
        try { jedis.del(key(sid)); } catch (Exception ignored) {}
    }

    private String key(String sid) { return "sess:" + sid; }

    public void setLegosetsList(List<LegoSet> legosets) {
        if (!available || jedis == null) return;
        try {
            String json = serialize(legosets);
            if (json != null) jedis.setex("legosets:list", ttlSeconds, json);
            logger.debug("setLegosetsList: saved {} items", legosets == null ? 0 : legosets.size());
        } catch (Exception e) {
            logger.error("Erro ao salvar legosets no Redis", e);
        }
    }

    public List<LegoSet> getLegosetsList() {
        if (!available || jedis == null) return Collections.emptyList();
        try {
            String json = jedis.get("legosets:list");
            if (json == null) {
                logger.debug("getLegosetsList: cache miss");
                return Collections.emptyList();
            }
            logger.debug("getLegosetsList: cache hit (bytes={})", json.length());
            return mapper.readValue(json, new TypeReference<List<LegoSet>>() {});
        } catch (Exception e) {
            logger.error("Erro ao recuperar legosets do Redis", e);
            return Collections.emptyList();
        }
    }

    public void setCommentsList(List<Comment> comments) {
        if (!available || jedis == null) return;
        try {
            String json = serialize(comments);
            if (json != null) jedis.setex("comments:list", ttlSeconds, json);
            logger.debug("setCommentsList: saved {} items", comments == null ? 0 : comments.size());
        } catch (Exception e) {
            logger.error("Erro ao salvar comentários no Redis", e);
        }
    }

    public List<Comment> getCommentsList() {
        if (!available || jedis == null) return Collections.emptyList();
        try {
            String data = jedis.get("comments:list");
            if (data == null) {
                logger.debug("getCommentsList: cache miss");
                return Collections.emptyList();
            }
            logger.debug("getCommentsList: cache hit (bytes={})", data.length());
            return mapper.readValue(data, mapper.getTypeFactory().constructCollectionType(List.class, Comment.class));
        } catch (Exception e) {
            logger.error("Erro ao recuperar comentários do Redis", e);
            return Collections.emptyList();
        }
    }

    // --- NEW: per-lego comments cache (recommended) ---
    public void setCommentsForLego(String legoId, List<Comment> comments) {
        if (!available || jedis == null || legoId == null) {
            logger.debug("setCommentsForLego: redis unavailable or legoId null");
            return;
        }
        try {
            String key = "comments:lego:" + legoId;
            String json = serialize(comments);
            if (json != null) {
                jedis.setex(key, ttlSeconds, json);
                logger.info("setCommentsForLego: saved {} items for legoId={}", comments == null ? 0 : comments.size(), legoId);
            } else {
                logger.warn("setCommentsForLego: serialization returned null for legoId={}", legoId);
            }
        } catch (Exception e) {
            logger.error("Erro ao salvar comentários por lego no Redis for legoId=" + legoId, e);
        }
    }

    public List<Comment> getCommentsForLego(String legoId) {
        if (!available || jedis == null || legoId == null) {
            logger.debug("getCommentsForLego: redis unavailable or legoId null");
            return Collections.emptyList();
        }
        try {
            String key = "comments:lego:" + legoId;
            String data = jedis.get(key);
            if (data == null) {
                logger.debug("getCommentsForLego: cache miss for legoId={}", legoId);
                return Collections.emptyList();
            }
            logger.debug("getCommentsForLego: cache hit for legoId={} (bytes={})", legoId, data.length());
            return mapper.readValue(data, mapper.getTypeFactory().constructCollectionType(List.class, Comment.class));
        } catch (Exception e) {
            logger.error("Erro ao recuperar comentários por lego do Redis for legoId=" + legoId, e);
            return Collections.emptyList();
        }
    }

    public void deleteCommentsForLego(String legoId) {
        if (!available || jedis == null || legoId == null) return;
        try { jedis.del("comments:lego:" + legoId); } catch (Exception e) { logger.error("Erro deleteCommentsForLego " + legoId, e); }
    }

    // small helpers already present: getRawLegosetsList/getRawCommentsList/exist/deleteKey etc.
    public String getRawLegosetsList() {
        if (!available || jedis == null) return null;
        try { return jedis.get("legosets:list"); } catch (Exception e) { logger.error("Erro getRawLegosetsList", e); return null; }
    }

    public String getRawCommentsList() {
        if (!available || jedis == null) return null;
        try { return jedis.get("comments:list"); } catch (Exception e) { logger.error("Erro getRawCommentsList", e); return null; }
    }

    public boolean existsKey(String key) {
        if (!available || jedis == null || key == null) return false;
        try { return jedis.exists(key); } catch (Exception e) { logger.error("Erro existsKey " + key, e); return false; }
    }

    public void deleteKey(String key) {
        if (!available || jedis == null || key == null) return;
        try { jedis.del(key); } catch (Exception e) { logger.error("Erro deleteKey " + key, e); }
    }

    private String serialize(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) {
            logger.error("Redis serialize EX", e);
            return null;
        }
    }

    // --- NEW: cache-aside helpers that load from Cosmos DB if cache miss ---
    public List<LegoSet> getOrLoadLegosetsList() {
        try {
            // try cache first
            List<LegoSet> cached = getLegosetsList();
            if (cached != null && !cached.isEmpty()) {
                logger.debug("getOrLoadLegosetsList: returning from cache (count={})", cached.size());
                return cached;
            }

            // cache miss -> load from CosmosDB
            CosmosDBLayer cosmos = CosmosDBLayer.getInstance();
            CosmosPagedIterable<LegoSetDAO> daos = cosmos.listLegoSets();
            List<LegoSet> result = new ArrayList<>();
            for (LegoSetDAO dao : daos) {
                result.add(mapper.convertValue(dao, LegoSet.class));
            }

            if (!result.isEmpty() && available && jedis != null) {
                setLegosetsList(result);
                logger.info("getOrLoadLegosetsList: populated cache with {} items", result.size());
            } else {
                logger.debug("getOrLoadLegosetsList: no items loaded from DB");
            }
            return result;
        } catch (Exception e) {
            logger.error("getOrLoadLegosetsList EX", e);
            return Collections.emptyList();
        }
    }

    public List<Comment> getOrLoadCommentsForLego(String legoId) {
        if (legoId == null) return Collections.emptyList();
        try {
            // try per-lego cache first
            List<Comment> cached = getCommentsForLego(legoId);
            if (cached != null && !cached.isEmpty()) {
                logger.debug("getOrLoadCommentsForLego: returning from cache for legoId={} (count={})", legoId, cached.size());
                return cached;
            }

            // cache miss -> load from CosmosDB
            CosmosDBLayer cosmos = CosmosDBLayer.getInstance();
            CosmosPagedIterable<CommentDAO> daos = cosmos.listCommentsByLegoSet(legoId);
            List<Comment> result = new ArrayList<>();
            for (CommentDAO dao : daos) {
                result.add(mapper.convertValue(dao, Comment.class));
            }

            if (!result.isEmpty() && available && jedis != null) {
                setCommentsForLego(legoId, result);
                logger.info("getOrLoadCommentsForLego: populated cache for legoId={} with {} items", legoId, result.size());
            } else {
                logger.debug("getOrLoadCommentsForLego: no comments loaded from DB for legoId={}", legoId);
            }
            return result;
        } catch (Exception e) {
            logger.error("getOrLoadCommentsForLego EX for legoId=" + legoId, e);
            return Collections.emptyList();
        }
    }
}