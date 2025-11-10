package cc.db;

import cc.data.auth.Session;
import cc.data.comment.Comment;
import cc.data.lego.LegoSet;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger logger = Logger.getLogger(RedisLayer.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private JedisPooled jedis;
    private boolean available = false;

    private final String REDIS_URL = System.getenv("REDIS_URL"); // pode ser rediss://:KEY@host:6380 ou apenas host
    private final String REDIS_KEY = System.getenv("REDIS_KEY"); // usado só se REDIS_URL for host simples
    private final int ttlSeconds = Integer.parseInt(System.getenv().getOrDefault("REDIS_TTL", "300"));

    private RedisLayer() {
        initClient();
    }

    private void initClient() {
        try {
            if (REDIS_URL == null || REDIS_URL.isBlank()) {
                logger.warning("REDIS_URL não definido; Redis OFF");
                available = false;
                return;
            }

            if (REDIS_URL.startsWith("redis://") || REDIS_URL.startsWith("rediss://")) {
                logger.info("A ligar ao Redis via URI: " + safeHost(REDIS_URL));
                jedis = new JedisPooled(URI.create(REDIS_URL));
            } else {
                if (REDIS_KEY == null || REDIS_KEY.isBlank()) {
                    logger.warning("REDIS_KEY ausente para hostname simples; Redis OFF");
                    available = false;
                    return;
                }
                logger.info("A ligar ao Redis (TLS) via HostAndPort: " + REDIS_URL + ":6380");
                var cfg = DefaultJedisClientConfig.builder()
                        .ssl(true)
                        .password(REDIS_KEY)
                        .timeoutMillis(3000)
                        .build();
                jedis = new JedisPooled(new HostAndPort(REDIS_URL, 6380), cfg);
            }

            available = "PONG".equalsIgnoreCase(jedis.ping());
            logger.info("Redis disponível: " + available);
        } catch (Exception e) {
            available = false;
            logger.log(Level.WARNING, "Falha a inicializar Redis", e);
        }
    }

    private String safeHost(String uri) {
        try {
            URI u = URI.create(uri);
            int port = u.getPort();
            if (port < 0) port = u.getScheme().equals("rediss") ? 6380 : 6379;
            return u.getHost() + ":" + port;
        } catch (Exception e) {
            return "<invalid-uri>";
        }
    }

    public boolean isAvailable() {
        return available && jedis != null;
    }

    // ---- Sessions ----
    public void putSession(Session s) {
        if (!isAvailable() || s == null) return;
        try {
            String sid = sessionId(s);
            if (sid == null || sid.isBlank()) return;
            jedis.setex("session:" + sid, ttlSeconds, mapper.writeValueAsString(s));
        } catch (Exception e) {
            logger.log(Level.FINE, "putSession falhou", e);
        }
    }

    public Session getSession(String sid) {
        if (!isAvailable() || sid == null || sid.isBlank()) return null;
        try {
            String v = jedis.get("session:" + sid);
            return v == null ? null : mapper.readValue(v, Session.class);
        } catch (Exception e) {
            logger.log(Level.FINE, "getSession falhou", e);
            return null;
        }
    }

    public void deleteSession(String sid) {
        if (!isAvailable() || sid == null || sid.isBlank()) return;
        try {
            jedis.del("session:" + sid);
        } catch (Exception e) {
            logger.log(Level.FINE, "deleteSession falhou", e);
        }
    }

    // Tenta obter um id/sid por reflexão para evitar dependência de getters específicos
    private String sessionId(Session s) {
        try {
            // tenta getId()
            Method m = s.getClass().getMethod("getId");
            Object v = m.invoke(s);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {}
        try {
            // tenta getSid()
            Method m = s.getClass().getMethod("getSid");
            Object v = m.invoke(s);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {}
        try {
            // tenta field "id"
            Field f = s.getClass().getDeclaredField("id");
            f.setAccessible(true);
            Object v = f.get(s);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {}
        try {
            // tenta field "sid"
            Field f = s.getClass().getDeclaredField("sid");
            f.setAccessible(true);
            Object v = f.get(s);
            if (v != null) return String.valueOf(v);
        } catch (Exception ignored) {}
        return null;
    }

    // ---- LegoSets (lista) ----
    public void setLegosetsList(List<LegoSet> legosets) {
        if (!isAvailable()) return;
        try {
            String json = mapper.writeValueAsString(legosets == null ? Collections.emptyList() : legosets);
            jedis.setex("legosets:list", ttlSeconds, json);
        } catch (Exception e) {
            logger.log(Level.FINE, "setLegosetsList falhou", e);
        }
    }

    public List<LegoSet> getLegosetsList() {
        if (!isAvailable()) return null;
        try {
            String v = jedis.get("legosets:list");
            if (v == null) return null;
            return mapper.readValue(v, new TypeReference<List<LegoSet>>() {});
        } catch (Exception e) {
            logger.log(Level.FINE, "getLegosetsList falhou", e);
            return null;
        }
    }

    public void deleteLegosetsList() {
        if (!isAvailable()) return;
        try {
            jedis.del("legosets:list");
        } catch (Exception e) {
            logger.log(Level.FINE, "deleteLegosetsList falhou", e);
        }
    }

    // ---- Comentários por Lego ----
    public void setCommentsForLego(String legoId, List<Comment> comments) {
        if (!isAvailable() || legoId == null || legoId.isBlank()) return;
        try {
            String key = "comments:lego:" + legoId;
            String json = mapper.writeValueAsString(comments == null ? Collections.emptyList() : comments);
            jedis.setex(key, ttlSeconds, json);
        } catch (Exception e) {
            logger.log(Level.FINE, "setCommentsForLego falhou", e);
        }
    }

    public List<Comment> getCommentsForLego(String legoId) {
        if (!isAvailable() || legoId == null || legoId.isBlank()) return null;
        try {
            String v = jedis.get("comments:lego:" + legoId);
            if (v == null) return null;
            return mapper.readValue(v, new TypeReference<List<Comment>>() {});
        } catch (Exception e) {
            logger.log(Level.FINE, "getCommentsForLego falhou", e);
            return null;
        }
    }


    public List<Comment> getOrLoadCommentsForLego(String legoId, Supplier<List<Comment>> dbLoader) {
        // 1) tentar cache
        List<Comment> cached = getCommentsForLego(legoId);
        if (cached != null) return cached;

        // 2) carregar do DB via loader
        List<Comment> fromDb = safeLoad(dbLoader);
        if (fromDb == null) fromDb = Collections.emptyList();

        // 3) escrever em cache
        setCommentsForLego(legoId, fromDb);
        return fromDb;
    }

    // ---- Lista de LegoSets ----
    public List<LegoSet> getOrLoadLegosetsList(Supplier<List<LegoSet>> dbLoader) {
        // 1) tentar cache
        List<LegoSet> cached = getLegosetsList();
        if (cached != null) return cached;

        // 2) carregar do DB via loader
        List<LegoSet> fromDb = safeLoad(dbLoader);
        if (fromDb == null) fromDb = Collections.emptyList();

        // 3) escrever em cache
        setLegosetsList(fromDb);
        return fromDb;
    }

    // Helper para proteger loaders
    private static <T> T safeLoad(Supplier<T> loader) {
        try { return loader == null ? null : loader.get(); }
        catch (Exception e) {
            logger.log(Level.FINE, "Loader falhou", e);
            return null;
        }
    }

    // Utilitário
    public void deleteKey(String key) {
        if (!isAvailable() || key == null || key.isBlank()) return;
        try {
            jedis.del(key);
        } catch (Exception e) {
            logger.log(Level.FINE, "deleteKey falhou", e);
        }
    }

    public void deleteCommentsForLego(String legoId) {
        if (!isAvailable() || legoId == null || legoId.isBlank()) return;
        try { jedis.del("comments:lego:" + legoId); } catch (Exception e) {
            logger.log(Level.FINE, "deleteCommentsForLego falhou", e);
        }
    }

    // Added: simple generic cache helpers
    public String getValue(String fullKey) {
        try {
            if (!available || jedis == null) return null;
            return jedis.get(fullKey);
        } catch (Exception e) {
            System.err.println("Redis getValue EX: " + e.getMessage());
            return null;
        }
    }

    public void putValue(String fullKey, String value) {
        try {
            if (!available || jedis == null) return;
            if (ttlSeconds > 0) {
                jedis.setex(fullKey, ttlSeconds, value);
            } else {
                jedis.set(fullKey, value);
            }
        } catch (Exception e) {
            System.err.println("Redis putValue EX: " + e.getMessage());
        }
    }

    public void delValue(String fullKey) {
        try {
            if (!available || jedis == null) return;
            jedis.del(fullKey);
        } catch (Exception e) {
            System.err.println("Redis delValue EX: " + e.getMessage());
        }
    }
}