package cc.db;

import cc.data.auction.AuctionDAO;
import cc.data.bid.Bid;
import cc.data.comment.CommentDAO;
import cc.data.comment.Comment;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
import cc.data.auth.Session;
import cc.utils.AzureProperties;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.*;
import com.azure.cosmos.util.CosmosPagedIterable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;
public class CosmosDBLayer {
    private static final Logger LOG = Logger.getLogger(CosmosDBLayer.class.getName());

    // singleton instance
    private static CosmosDBLayer instance;

    // try environment first, then fallback to azurekeys.props
    private static final String CONNECTION_URL;
    private static final String DB_KEY;
    private static final String DB_NAME;

    // shared Cosmos objects (declare once)
    private CosmosClient client;
    private CosmosDatabase db;
    private CosmosContainer legoDescriptions;
    private CosmosContainer users;
    private CosmosContainer legosets;
    private CosmosContainer comments;
    private CosmosContainer auctions;
    private CosmosContainer sessions;
    
    static {
        String url = System.getenv("DB_URL");
        String key = System.getenv("DB_KEY");
        String dbName = System.getenv("DB_NAME");
        try {
            Properties p = AzureProperties.getProperties();
            if ((url == null || url.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_URL) != null)
                url = p.getProperty(AzureProperties.COSMOSDB_URL);
            if ((key == null || key.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_KEY) != null)
                key = p.getProperty(AzureProperties.COSMOSDB_KEY);
            if ((dbName == null || dbName.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_DATABASE) != null)
                dbName = p.getProperty(AzureProperties.COSMOSDB_DATABASE);
        } catch (Exception ignored) {}
        CONNECTION_URL = url != null ? url : "";
        DB_KEY = key != null ? key : "";
        DB_NAME = dbName != null ? dbName : "ccdb";
    }

    private CosmosDBLayer() {
        // private ctor for singleton
    }

    // singleton accessor used by resources
    public static synchronized CosmosDBLayer getInstance() {
        if (instance == null) {
            instance = new CosmosDBLayer();
            instance.init();
        }
        return instance;
    }

    private void init() {
        if (db != null) return;

        // build client if credentials present
        if (!CONNECTION_URL.isEmpty() && !DB_KEY.isEmpty()) {
            client = new CosmosClientBuilder()
                    .endpoint(CONNECTION_URL)
                    .key(DB_KEY)
                    .consistencyLevel(ConsistencyLevel.SESSION)
                    .buildClient();
        } else {
            LOG.warning("CosmosDBLayer.init: DB_URL or DB_KEY not provided; Cosmos client will not be initialized.");
        }

        // ensure database exists
        try { if (client != null) client.createDatabaseIfNotExists(DB_NAME); } catch (Exception ignored) {}
        db = client != null ? client.getDatabase(DB_NAME) : null;

        if (db == null) {
            LOG.warning("CosmosDBLayer.init: database is null; skipping container initialization.");
            return;
        }

        // ensure containers exist (use /id or proper partition key)
        try { db.createContainerIfNotExists(new CosmosContainerProperties("users", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("legosets", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("comments", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("auctions", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("sessions", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("lego_descriptions", "/legoSetId")); } catch (Exception ignored) {}

        users = db.getContainer("users");
        legosets = db.getContainer("legosets");
        comments = db.getContainer("comments");
        auctions = db.getContainer("auctions");
        sessions = db.getContainer("sessions");
        legoDescriptions = db.getContainer("lego_descriptions");
        LOG.info("CosmosDBLayer.init: legoDescriptions = " + (legoDescriptions != null));
    }

    // --- User Methods ---
    public UserDAO createUser(UserDAO user) {
        init();
        return users.createItem(user).getItem();
    }

    public UserDAO getUser(String id) {
        init();
        CosmosPagedIterable<UserDAO> result = users.queryItems("SELECT * FROM c WHERE c.id=\"" + id + "\"",
                new CosmosQueryRequestOptions(), UserDAO.class);
        return result.iterator().hasNext() ? result.iterator().next() : null;
    }

    public CosmosPagedIterable<UserDAO> listUsers() {
        init();
        return users.queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), UserDAO.class);
    }

    public UserDAO updateUser(UserDAO user) {
        init();
        return users.upsertItem(user, new PartitionKey(user.getId()), new CosmosItemRequestOptions()).getItem();
    }

    public void deleteUser(String id) {
        init();
        String deletedUserId = "deleted-user";

        for (LegoSetDAO ls : listLegoSetsOfUser(id)) {
            ls.setOwnerId(deletedUserId);
            updateLegoSet(ls);
        }

        for (CommentDAO c : listCommentsByUser(id)) {
            c.setUserId(deletedUserId);
            updateComment(c);
        }

        for (AuctionDAO a : listAuctionsOfUser(id)) {
            a.setSellerId(deletedUserId);
            updateAuction(a);
        }

        users.deleteItem(id, new PartitionKey(id), new CosmosItemRequestOptions());
    }


    public void createDeletedUserIfNotExists() {
        init();
        String deletedUserId = "deleted-user";

        UserDAO u = getUser(deletedUserId);
        if (u == null) {
            UserDAO deletedUser = new UserDAO();
            deletedUser.setId(deletedUserId);
            //deletedUser.setNickname("Deleted User");
            deletedUser.setName("Deleted User");
            deletedUser.setPwd("");
            deletedUser.setPhotoId(null);
            deletedUser.setLegoIds(new String[0]);
            createUser(deletedUser);
        }
    }
    public CosmosPagedIterable<CommentDAO> listCommentsByUser(String userId) {
        init();
        return comments.queryItems("SELECT * FROM c WHERE c.userId = '" + userId + "'", null, CommentDAO.class);
    }

    public CosmosPagedIterable<AuctionDAO> listAuctionsOfUser(String userId) {
        init();
        return auctions.queryItems("SELECT * FROM c WHERE c.sellerId = '" + userId + "'", null, AuctionDAO.class);
    }

    public CommentDAO updateComment(CommentDAO comment) {
        init();
        return comments.replaceItem(
                comment,
                comment.getId(),
                new PartitionKey(comment.getId()),
                null
        ).getItem();
    }

    public AuctionDAO updateAuction(AuctionDAO auction) {
        init();
        return auctions.replaceItem(
                auction,
                auction.getId(),
                new PartitionKey(auction.getId()),
                null
        ).getItem();
    }
    
    public UserDAO findUserByNickname(String name) {
        init();

        String sql = "SELECT * FROM c WHERE c.nickname = @nickname";
        List<SqlParameter> params = List.of(new SqlParameter("@nickname", name));

        SqlQuerySpec spec = new SqlQuerySpec(sql, params);

        CosmosPagedIterable<UserDAO> result =
                users.queryItems(spec, new CosmosQueryRequestOptions(), UserDAO.class);

        return result.iterator().hasNext() ? result.iterator().next() : null;
    }

    // --- LegoSet Methods ---
    public LegoSetDAO createLegoSet(LegoSetDAO ls) {
        init();
        return legosets.createItem(ls).getItem();
    }

    public LegoSetDAO getLegoSet(String id) {
        init();
        var results = legosets.queryItems(
                "SELECT * FROM c WHERE c.id='" + id + "'",
                null,
                LegoSetDAO.class
        );
        return results.iterator().hasNext()
                ? results.iterator().next()
                : null;
    }

    public CosmosPagedIterable<LegoSetDAO> listLegoSets() {
        init();
        return legosets.queryItems("SELECT * FROM c", null, LegoSetDAO.class);
    }

    public LegoSetDAO updateLegoSet(LegoSetDAO ls) {
        init();
        return legosets.replaceItem(ls, ls.getId(), new PartitionKey(ls.getId()), new CosmosItemRequestOptions()).getItem();
    }

    public void deleteLegoSet(String id) {
        init();
        legosets.deleteItem(id, new PartitionKey(id), new CosmosItemRequestOptions());
    }

    public CosmosPagedIterable<LegoSetDAO> listLegoSetsOfUser(String userId) {
        init();
        return legosets.queryItems("SELECT * FROM c WHERE c.ownerId = '" + userId + "'", null, LegoSetDAO.class);
    }

    public CosmosPagedIterable<LegoSetDAO> listMostRecentLegoSets(int offset, int limit) {
        init();
        return legosets.queryItems("SELECT * FROM c ORDER BY c._ts DESC OFFSET " + offset + " LIMIT " + limit, null,
                LegoSetDAO.class);
    }

    // --- Comment Methods (mínimo para cache) ---
    // Container já inicializado em init(): comments
    public CommentDAO createComment(CommentDAO dao) {
        init();
        // PartitionKey = legoSetId (assumindo modelo)
        comments.createItem(dao, new PartitionKey(dao.getLegoSetId()), new CosmosItemRequestOptions());
        return dao;
    }

    public List<CommentDAO> listCommentsByLegoSet(String legoSetId) {
        init();
        if (legoSetId == null || legoSetId.isBlank()) return Collections.emptyList();
        String q = "SELECT * FROM c WHERE c.legoSetId = @lid";
        SqlQuerySpec spec = new SqlQuerySpec(q, Collections.singletonList(new SqlParameter("@lid", legoSetId)));
        List<CommentDAO> out = new ArrayList<>();
        comments.queryItems(spec, new CosmosQueryRequestOptions(), CommentDAO.class)
                .forEach(out::add);
        return out;
    }

    // --- Auction Methods ---
    public AuctionDAO createAuction(AuctionDAO auction) {
        init();
        try {
            LOG.info("createAuction: upserting auction id=" + auction.getId() + " legoSetId=" + auction.getLegoSetId() + " sellerId=" + auction.getSellerId());
            CosmosItemResponse<AuctionDAO> resp = auctions.upsertItem(auction);
            LOG.info("createAuction: upserted id=" + resp.getItem().getId() + " statusCode=" + resp.getStatusCode() + " _rid=" + resp.getItem().get_rid());
            return resp.getItem();
        } catch (Exception e) {
            LOG.severe("createAuction: err=" + e.getMessage());
            throw e;
        }
    }

    public CosmosPagedIterable<AuctionDAO> listAuctions(int offset, int limit) {
        init();
        String sql = "SELECT * FROM c ORDER BY c._ts DESC OFFSET " + offset + " LIMIT " + limit;
        LOG.info("listAuctions: sql=" + sql);
        return auctions.queryItems(sql, new CosmosQueryRequestOptions(), AuctionDAO.class);
    }

    // return open auctions: not closed and closeDate > now
    public CosmosPagedIterable<AuctionDAO> listOpenAuctions(int offset, int limit) {
        init();
        long now = System.currentTimeMillis();
        String sql = "SELECT * FROM c WHERE (c.closed != true OR IS_NULL(c.closed)) AND c.closeDate > " + now
                + " ORDER BY c._ts DESC OFFSET " + offset + " LIMIT " + limit;
        LOG.info("listOpenAuctions: sql=" + sql);
        return auctions.queryItems(sql, new CosmosQueryRequestOptions(), AuctionDAO.class);
    }

    // return closed/past auctions: closed == true OR closeDate <= now
    public CosmosPagedIterable<AuctionDAO> listClosedAuctions(int offset, int limit) {
        init();
        long now = System.currentTimeMillis();
        String sql = "SELECT * FROM c WHERE (c.closed = true) OR (c.closeDate <= " + now + ") ORDER BY c._ts DESC OFFSET " + offset + " LIMIT " + limit;
        LOG.info("listClosedAuctions: sql=" + sql);
        return auctions.queryItems(sql, new CosmosQueryRequestOptions(), AuctionDAO.class);
    }

    public AuctionDAO getAuction(String id) {
        init();
        try {
            LOG.info("getAuction: id=" + id + " (searching cross-partition)");
            // avoid SqlParameterList (not present); use simple query string
            String sql = "SELECT * FROM c WHERE c.id = '" + id + "'";
            CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
            opts.setQueryMetricsEnabled(false);
            CosmosPagedIterable<AuctionDAO> results = auctions.queryItems(sql, opts, AuctionDAO.class);
            for (AuctionDAO a : results) {
                LOG.info("getAuction: found auction id=" + a.getId() + " sellerId=" + a.getSellerId() + " _rid=" + a.get_rid());
                return a;
            }
            LOG.info("getAuction: not found id=" + id);
        } catch (Exception e) {
            LOG.warning("getAuction: err=" + e.getMessage());
        }
        return null;
    }

    public void addBidToAuction(String auctionId, Bid bid) {
        init();
        try {
            LOG.info("addBidToAuction: searching auctionId=" + auctionId);

            // Procurar o leilão
            String sql = "SELECT * FROM c WHERE c.id = '" + auctionId + "'";
            CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
            opts.setQueryMetricsEnabled(false);
            CosmosPagedIterable<AuctionDAO> results = auctions.queryItems(sql, opts, AuctionDAO.class);

            for (AuctionDAO auction : results) {
                LOG.info("addBidToAuction: found auction id=" + auction.getId() +
                        " sellerId=" + auction.getSellerId());

                // Adicionar o novo bid
                if (auction.getBids() == null)
                    auction.setBids(new ArrayList<>());
                auction.getBids().add(bid);

                // Atualizar documento
                auctions.upsertItem(auction);
                LOG.info("addBidToAuction: bid added successfully for auctionId=" + auctionId);
                return;
            }

            LOG.warning("addBidToAuction: auction not found id=" + auctionId);
        } catch (Exception e) {
            LOG.warning("addBidToAuction: err=" + e.getMessage());
        }
    }

    public CosmosPagedIterable<AuctionDAO> listExpiredAuctions() {
		init();
        return auctions.queryItems(
                "SELECT * FROM c WHERE c.closed != true AND c.closeDate < " + System.currentTimeMillis(),
                null, AuctionDAO.class);
	}

    public CosmosPagedIterable<AuctionDAO> searchAuctionForLegoSet(String legoSetId) {
        init();
        return auctions.queryItems(
                "SELECT * FROM c WHERE c.legoSetId = '" + legoSetId + "' AND c.closeDate > "
                        + System.currentTimeMillis(),
                null, AuctionDAO.class);
    }

    private long countItemsInPeriod(CosmosContainer container, int seconds) {
        init();
        long timeBoundary = (System.currentTimeMillis() / 1000L) - seconds;
        String query = "SELECT VALUE COUNT(1) FROM c WHERE c._ts > " + timeBoundary;
        
        CosmosPagedIterable<Long> result = container.queryItems(query, new CosmosQueryRequestOptions(), Long.class);
        return result.iterator().hasNext() ? result.iterator().next() : 0;
    }

    public long countNewUsersInLast3Minutes() {
        return countItemsInPeriod(users, 3 * 60);
    }

    public long countNewAuctionsInLast3Minutes() {
        return countItemsInPeriod(auctions, 3 * 60);
    }

    public long countNewBidsInLast3Minutes() {
        init();
        long timeBoundary = System.currentTimeMillis() - (3 * 60 * 1000);
        String query = "SELECT * FROM c WHERE c._ts > " + ((System.currentTimeMillis() / 1000L) - (3*60));
        
        AtomicInteger bidCount = new AtomicInteger(0);
        auctions.queryItems(query, new CosmosQueryRequestOptions(), AuctionDAO.class)
                .forEach(auction -> {
                    if (auction.getBids() != null) {
                        auction.getBids().forEach(bid -> {
                            if (bid.getTimestamp() > timeBoundary) {
                                bidCount.incrementAndGet();
                            }
                        });
                    }
                });
        return bidCount.get();
    }
    
    public int deleteExpiredSessions() {
    init();
    long now = System.currentTimeMillis();
    String query = "SELECT * FROM c WHERE c.expiration < " + now;
    
    AtomicInteger deletedCount = new AtomicInteger(0);
    CosmosPagedIterable<Session> expired = sessions.queryItems(query, new CosmosQueryRequestOptions(), Session.class);

    expired.forEach(session -> {
        try {
            // Corrigido para usar getSid()
            sessions.deleteItem(session.getSid(), new PartitionKey(session.getSid()), new CosmosItemRequestOptions());
            deletedCount.incrementAndGet();
        } catch (Exception e) {
            // Corrigido para usar getSid()
            LOG.warning("Falha ao apagar sessão expirada: " + session.getSid() + " - Erro: " + e.getMessage());
        }
    });
    
    return deletedCount.get();
}


    public void close() {
        client.close();
    }

    public void upsertLegoDescription(String legoSetId, String description, String[] tags) {
        init();
        try {
            Map<String,Object> doc = new HashMap<>();
            // use legoSetId as id to ensure one description per lego set
            doc.put("id", legoSetId);
            doc.put("legoSetId", legoSetId);
            doc.put("description", description);
            doc.put("tags", tags);
            doc.put("updatedAt", System.currentTimeMillis());
            CosmosItemResponse<Object> resp = legoDescriptions.upsertItem(doc);
            LOG.info("upsertLegoDescription: upserted lego_descriptions id=" + legoSetId + " status=" + resp.getStatusCode());
        } catch (Exception e) {
            LOG.warning("upsertLegoDescription: err=" + e.getMessage());
        }
    }

    // retorna o documento em lego_descriptions ou null
    @SuppressWarnings("unchecked")
    public Map<String,Object> getLegoDescription(String legoSetId) {
        init();
        if (legoDescriptions == null) {
            LOG.warning("getLegoDescription: legoDescriptions container is null");
            return null;
        }
        try {
            LOG.info("getLegoDescription: reading id=" + legoSetId + " partition=/legoSetId");
            CosmosItemResponse<Map> resp = legoDescriptions.readItem(legoSetId, new PartitionKey(legoSetId), Map.class);
            LOG.info("getLegoDescription: read status=" + resp.getStatusCode());
            return resp.getItem();
        } catch (Exception e) {
            LOG.info("getLegoDescription: direct read failed for id=" + legoSetId + " err=" + e.getMessage());
            // fallback a query
            try {
                String sql = "SELECT * FROM c WHERE c.id = '" + legoSetId + "'";
                CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
                CosmosPagedIterable<Map> results = legoDescriptions.queryItems(sql, opts, Map.class);
                if (results.iterator().hasNext()) {
                    LOG.info("getLegoDescription: query fallback found item");
                    return results.iterator().next();
                } else {
                    LOG.info("getLegoDescription: query fallback found nothing");
                }
            } catch (Exception ex) {
                LOG.warning("getLegoDescription: query fallback failed for id=" + legoSetId + " err=" + ex.getMessage());
            }
        }
        return null;
    }
}