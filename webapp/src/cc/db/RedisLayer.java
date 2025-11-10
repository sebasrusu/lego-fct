package cc.db;

import cc.data.auth.Session;
import cc.data.comment.Comment;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSet;
import cc.data.lego.LegoSetDAO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azure.cosmos.util.CosmosPagedIterable;

// --- Imports de Log Corrigidos ---
import java.util.logging.Level;
import java.util.logging.Logger;
// --- Fim dos Imports de Log ---

import redis.clients.jedis.JedisPooled;

import java.net.URI;
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

    // --- Logger Corrigido (igual ao CosmosDBLayer) ---
    private static final Logger logger = Logger.getLogger(RedisLayer.class.getName());

    private RedisLayer() {
        String url = System.getenv("REDIS_URL");
        String key = System.getenv("REDIS_KEY");
        REDIS_URL = url;
        REDIS_KEY = key;
        initClient();
    }

    // --- initClient com o construtor URI correto E logging corrigido ---
    private void initClient() {
        if (REDIS_URL == null || REDIS_URL.isBlank()) {
            available = false;
            logger.warning("Redis URL não configurada (REDIS_URL), Redis desativado.");
            return;
        }
        
        if (REDIS_KEY == null || REDIS_KEY.isBlank()) {
            available = false;
            logger.warning("Redis Key não configurada (REDIS_KEY), Redis desativado.");
            return;
        }
        
        try {
            String host = REDIS_URL;
            int port = 6380; // Porta SSL por defeito

            if (REDIS_URL.contains(":")) {
                String[] parts = REDIS_URL.split(":", 2);
                host = parts[0];
                try { port = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
            }

            // rediss://:PASSWORD@HOST:PORT
            String uriString = String.format("rediss://:%s@%s:%d", REDIS_KEY, host, port);
            String loggedUri = String.format("rediss://:[PASSWORD]@%s:%d", host, port);
            
            // Log que AGORA VAI APARECER
            logger.info("A tentar ligar ao Redis via URI: " + loggedUri);

            jedis = new JedisPooled(new URI(uriString));

            // Testar a conexão
            String pingResult = jedis.ping();
            
            available = true;
            // Log que AGORA VAI APARECER
            logger.info("Cliente Redis inicializado com sucesso. Ping: " + pingResult);
            
        } catch (Throwable t) { 
            available = false;
            // Log de ERRO que AGORA VAI APARECER
            logger.log(Level.SEVERE, "Falha ao inicializar cliente Redis", t);
        }
    }


    public boolean isAvailable() { return available; }

    public void putSession(Session s) {
        if (!available || jedis == null || s == null || s.getSid() == null) {
            if(!available) logger.warning("putSession: Redis não está disponível.");
            return;
        }
        try {
            String k = key(s.getSid());
            String json = mapper.writeValueAsString(s);
            jedis.setex(k, ttlSeconds, json);
            logger.fine("putSession: Sessão guardada " + k); // 'fine' é o 'debug'
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Redis putSession EX", e);
        }
    }

    public Session getSession(String sid) {
        if (!available || jedis == null || sid == null) {
             if(!available) logger.warning("getSession: Redis não está disponível.");
            return null;
        }
        try {
            String k = key(sid);
            String json = jedis.get(k);
            if(json == null) logger.fine("getSession: Cache miss " + k);
            else logger.fine("getSession: Cache hit " + k);
            return json == null ? null : mapper.readValue(json, Session.class);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Redis getSession EX", e);
            return null;
        }
    }

    public void deleteSession(String sid) {
        if (!available || jedis == null || sid == null) return;
        try { 
            logger.fine("deleteSession: A apagar " + key(sid));
            jedis.del(key(sid)); 
        } catch (Exception ignored) {}
    }

    private String key(String sid) { return "sess:" + sid; }

    public void setLegosetsList(List<LegoSet> legosets) {
        if (!available || jedis == null) {
             if(!available) logger.warning("setLegosetsList: Redis não está disponível.");
             return;
        }
        try {
            String json = serialize(legosets);
            if (json != null) jedis.setex("legosets:list", ttlSeconds, json);
            logger.fine("setLegosetsList: guardados " + (legosets == null ? 0 : legosets.size()) + " items");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao salvar legosets no Redis", e);
        }
    }

    public List<LegoSet> getLegosetsList() {
        if (!available || jedis == null) {
            if(!available) logger.warning("getLegosetsList: Redis não está disponível.");
            return Collections.emptyList();
        }
        try {
            String json = jedis.get("legosets:list");
            if (json == null) {
                logger.fine("getLegosetsList: cache miss");
                return Collections.emptyList();
            }
            logger.fine("getLegosetsList: cache hit (bytes=" + json.length() + ")");
            return mapper.readValue(json, new TypeReference<List<LegoSet>>() {});
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao recuperar legosets do Redis", e);
            return Collections.emptyList();
        }
    }

    // --- NEW: per-lego comments cache (recommended) ---
    public void setCommentsForLego(String legoId, List<Comment> comments) {
        if (!available || jedis == null || legoId == null) {
            if(!available) logger.warning("setCommentsForLego: Redis não está disponível.");
            return;
        }
        try {
            String key = "comments:lego:" + legoId;
            String json = serialize(comments);
            if (json != null) {
                jedis.setex(key, ttlSeconds, json);
                logger.info("setCommentsForLego: guardados " + (comments == null ? 0 : comments.size()) + " items para legoId=" + legoId);
            } else {
                logger.warning("setCommentsForLego: serialização devolveu null para legoId=" + legoId);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao salvar comentários por lego no Redis for legoId=" + legoId, e);
        }
    }

    public List<Comment> getCommentsForLego(String legoId) {
        if (!available || jedis == null || legoId == null) {
            if(!available) logger.warning("getCommentsForLego: Redis não está disponível.");
            return Collections.emptyList();
        }
        try {
            String key = "comments:lego:" + legoId;
            String data = jedis.get(key);
            if (data == null) {
                logger.fine("getCommentsForLego: cache miss para legoId=" + legoId);
                return Collections.emptyList();
            }
            logger.fine("getCommentsForLego: cache hit para legoId=" + legoId + " (bytes=" + data.length() + ")");
            return mapper.readValue(data, mapper.getTypeFactory().constructCollectionType(List.class, Comment.class));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao recuperar comentários por lego do Redis for legoId=" + legoId, e);
            return Collections.emptyList();
        }
    }

    public void deleteCommentsForLego(String legoId) {
        if (!available || jedis == null || legoId == null) return;
        try { 
            String key = "comments:lego:" + legoId;
            logger.fine("deleteCommentsForLego: A apagar " + key);
            jedis.del(key); 
        } catch (Exception e) { logger.log(Level.SEVERE, "Erro deleteCommentsForLego " + legoId, e); }
    }
    
    public String getRawLegosetsList() {
        if (!available || jedis == null) return null;
        try { return jedis.get("legosets:list"); } catch (Exception e) { logger.log(Level.SEVERE, "Erro getRawLegosetsList", e); return null; }
    }

    public String getRawCommentsList() {
        if (!available || jedis == null) return null;
        try { return jedis.get("comments:list"); } catch (Exception e) { logger.log(Level.SEVERE, "Erro getRawCommentsList", e); return null; }
    }

    public boolean existsKey(String key) {
        if (!available || jedis == null || key == null) return false;
        try { return jedis.exists(key); } catch (Exception e) { logger.log(Level.SEVERE, "Erro existsKey " + key, e); return false; }
    }

    public void deleteKey(String key) {
        if (!available || jedis == null || key == null) return;
        try { 
            logger.fine("deleteKey: A apagar " + key);
            jedis.del(key); 
        } catch (Exception e) { logger.log(Level.SEVERE, "Erro deleteKey " + key, e); }
    }

    private String serialize(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) {
            logger.log(Level.SEVERE, "Redis serialize EX", e);
            return null;
        }
    }

    // --- Métodos getOrLoad (Cache-Aside) ---

    public List<LegoSet> getOrLoadLegosetsList() {
        try {
            List<LegoSet> cached = getLegosetsList();
            if (cached != null && !cached.isEmpty()) {
                logger.fine("getOrLoadLegosetsList: a devolver da cache (count=" + cached.size() + ")");
                return cached;
            }

            CosmosDBLayer cosmos = CosmosDBLayer.getInstance();
            CosmosPagedIterable<LegoSetDAO> daos = cosmos.listLegoSets();
            List<LegoSet> result = new ArrayList<>();
            for (LegoSetDAO dao : daos) {
                result.add(mapper.convertValue(dao, LegoSet.class));
            }

            if (!result.isEmpty() && available && jedis != null) {
                setLegosetsList(result);
                logger.info("getOrLoadLegosetsList: cache populada com " + result.size() + " items");
            } else {
                logger.fine("getOrLoadLegosetsList: 0 items da DB");
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "getOrLoadLegosetsList EX", e);
            return Collections.emptyList();
        }
    }

    public List<Comment> getOrLoadCommentsForLego(String legoId) {
        if (legoId == null) return Collections.emptyList();
        try {
            List<Comment> cached = getCommentsForLego(legoId);
            if (cached != null && !cached.isEmpty()) {
                logger.fine("getOrLoadCommentsForLego: a devolver da cache para legoId=" + legoId + " (count=" + cached.size() + ")");
                return cached;
            }

            CosmosDBLayer cosmos = CosmosDBLayer.getInstance();
            CosmosPagedIterable<CommentDAO> daos = cosmos.listCommentsByLegoSet(legoId);
            List<Comment> result = new ArrayList<>();
            for (CommentDAO dao : daos) {
                result.add(mapper.convertValue(dao, Comment.class));
            }

            if (available && jedis != null) { // Guardar mesmo se estiver vazio
                setCommentsForLego(legoId, result);
                logger.info("getOrLoadCommentsForLego: cache populada para legoId=" + legoId + " com " + result.size() + " items");
            } else {
                logger.fine("getOrLoadCommentsForLego: 0 comentários da DB para legoId=" + legoId);
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "getOrLoadCommentsForLego EX para legoId=" + legoId, e);
            return Collections.emptyList();
        }
    }
}