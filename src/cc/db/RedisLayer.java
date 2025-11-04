package cc.db;

import cc.utils.AzureProperties;
import cc.data.auth.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

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

    private RedisLayer() {
        String url = System.getenv("REDIS_URL");
        String key = System.getenv("REDIS_KEY");
        try {
            Properties p = AzureProperties.getProperties();
            if ((url == null || url.isEmpty()) && p.getProperty(AzureProperties.REDIS_URL) != null)
                url = p.getProperty(AzureProperties.REDIS_URL);
            if ((key == null || key.isEmpty()) && p.getProperty(AzureProperties.REDIS_KEY) != null)
                key = p.getProperty(AzureProperties.REDIS_KEY);
        } catch (Exception ignored) {}
        REDIS_URL = url;
        REDIS_KEY = key;
        initClient();
    }

    private void initClient() {
        if (REDIS_URL == null || REDIS_URL.isBlank()) {
            System.err.println("RedisLayer: REDIS_URL em falta. Redis desativado.");
            available = false;
            return;
        }
        try {
            String uri;
            if (REDIS_KEY != null && !REDIS_KEY.isBlank()) {
                String enc = URLEncoder.encode(REDIS_KEY, StandardCharsets.UTF_8);
                if (REDIS_URL.contains("://")) {
                    uri = REDIS_URL;
                } else {
                    uri = "rediss://:" + enc + "@" + REDIS_URL;
                    if (!REDIS_URL.contains(":")) uri += ":6380";
                }
            } else {
                if (REDIS_URL.contains("://")) uri = REDIS_URL;
                else {
                    uri = "redis://" + REDIS_URL;
                    if (!REDIS_URL.contains(":")) uri += ":6379";
                }
            }
            jedis = new JedisPooled(uri);
            available = true;
            System.err.println("RedisLayer: inicializado com uri=" + (uri.length() > 60 ? uri.substring(0, 60) + "..." : uri));
        } catch (Throwable t) {
            available = false;
            System.err.println("RedisLayer init failed: " + t.getMessage());
        }
    }

    public boolean isAvailable() { return available; }

    public void putSession(Session s) {
        if (!available || jedis == null) return;
        try { jedis.setex(key(s.getSid()), ttlSeconds, mapper.writeValueAsString(s)); }
        catch (Exception e) { System.err.println("Redis putSession failed: " + e.getMessage()); }
    }

    public Session getSession(String sid) {
        if (!available || jedis == null) return null;
        try {
            String json = jedis.get(key(sid));
            return json == null ? null : mapper.readValue(json, Session.class);
        } catch (Exception e) { return null; }
    }

    public void deleteSession(String sid) {
        if (!available || jedis == null) return;
        try { jedis.del(key(sid)); } catch (Exception ignored) {}
    }

    private String key(String sid) { return "sess:" + sid; }
}
