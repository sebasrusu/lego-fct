package cc.db;

import cc.data.auth.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

public class RedisLayer {
    private static final RedisLayer INSTANCE = new RedisLayer();
    public static RedisLayer getInstance() { return INSTANCE; }

    private final JedisPooled jedis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int ttlSeconds = 3600; // 1h

    private RedisLayer() {
        String url = System.getenv("REDIS_URL"); // rediss://:<key>@<host>:6380
        if (url == null) throw new IllegalStateException("REDIS_URL em falta.");
        jedis = new JedisPooled(url);
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
