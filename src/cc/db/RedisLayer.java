package cc.db;

import cc.utils.AzureProperties;
import cc.data.auth.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

import java.util.Properties;

public class RedisLayer {
    private static final RedisLayer INSTANCE = new RedisLayer();
    public static RedisLayer getInstance() { return INSTANCE; }

    private final JedisPooled jedis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int ttlSeconds = 3600; // 1h

    // try env first, then azurekeys.props
    private static final String REDIS_URL;
    private static final String REDIS_KEY;

    static {
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
    }

    private RedisLayer() {
        if (REDIS_URL == null) throw new IllegalStateException("REDIS_URL em falta.");
        jedis = new JedisPooled(REDIS_URL);
    }

    public void putSession(Session s) {
        try { jedis.setex(key(s.getSid()), ttlSeconds, mapper.writeValueAsString(s)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public Session getSession(String sid) {
        try {
            String json = jedis.get(key(sid));
            return json == null ? null : mapper.readValue(json, Session.class);
        } catch (Exception e) { return null; }
    }

    public void deleteSession(String sid) { jedis.del(key(sid)); }

    private String key(String sid) { return "sess:" + sid; }
}
