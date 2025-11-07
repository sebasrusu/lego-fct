package cc.db;

import cc.data.auction.AuctionDAO;
import cc.data.bid.Bid;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
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
import java.util.logging.Logger;

public class CosmosDBLayer {
    // try environment first, then fallback to azurekeys.props
    private static final String CONNECTION_URL;
    private static final String DB_KEY;
    private static final String DB_NAME;

    static {
        String url = System.getenv("DB_URL");
        String key = System.getenv("DB_KEY");
        String name = System.getenv("DB_NAME");
        try {
            Properties p = AzureProperties.getProperties();
            if ((url == null || url.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_URL) != null)
                url = p.getProperty(AzureProperties.COSMOSDB_URL);
            if ((key == null || key.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_KEY) != null)
                key = p.getProperty(AzureProperties.COSMOSDB_KEY);
            if ((name == null || name.isEmpty()) && p.getProperty(AzureProperties.COSMOSDB_DATABASE) != null)
                name = p.getProperty(AzureProperties.COSMOSDB_DATABASE);
        } catch (Exception ignored) {}
        CONNECTION_URL = url;
        DB_KEY = key;
        DB_NAME = name;
    }

    private static CosmosDBLayer instance;

    private CosmosClient client;
    private CosmosDatabase db;
    private CosmosContainer users;
    private CosmosContainer legosets;
    private CosmosContainer comments;
    private CosmosContainer auctions;

    // logger
    private static final Logger LOG = Logger.getLogger(CosmosDBLayer.class.getName());

    private CosmosDBLayer(CosmosClient client) {
        this.client = client;
    }

    public static synchronized CosmosDBLayer getInstance() {
        if (instance != null)
            return instance;

        if (CONNECTION_URL == null || DB_KEY == null || DB_NAME == null) {
            throw new IllegalStateException(
                    "Config em falta: DB_URL/DB_KEY/DB_NAME. Verifica App Settings ou flags -D.");
        }

        CosmosClient client = new CosmosClientBuilder().endpoint(CONNECTION_URL).key(DB_KEY)
                .consistencyLevel(ConsistencyLevel.SESSION)
                .connectionSharingAcrossClientsEnabled(true)
                .contentResponseOnWriteEnabled(true).buildClient();
        instance = new CosmosDBLayer(client);
        return instance;
    }

    private synchronized void init() {
        if (db != null)
            return;
        // ensure database exists
        try { client.createDatabaseIfNotExists(DB_NAME); } catch (Exception ignored) {}
        db = client.getDatabase(DB_NAME);
        // ensure containers exist (use /id as partition key for simplicity; adapt if needed)
        try { db.createContainerIfNotExists(new CosmosContainerProperties("users", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("legosets", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("comments", "/id")); } catch (Exception ignored) {}
        try { db.createContainerIfNotExists(new CosmosContainerProperties("auctions", "/id")); } catch (Exception ignored) {}
        users = db.getContainer("users");
        legosets = db.getContainer("legosets");
        comments = db.getContainer("comments");
        auctions = db.getContainer("auctions");
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

    // --- Comment Methods ---
    public void createComment(CommentDAO comment) {
        init();
        comments.createItem(comment);
    }

    public CosmosPagedIterable<CommentDAO> listCommentsByLegoSet(String legoSetId) {
        init();
        return comments.queryItems("SELECT * FROM c WHERE c.legoSetId = '" + legoSetId + "'", null, CommentDAO.class);
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

    // return ALL auctions (no date filter)
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
        AuctionDAO auction = getAuction(auctionId);
        if (auction == null) {
            throw new IllegalArgumentException("Auction not found: " + auctionId);
        }

        try {
            String pkPath = auctions.read().getProperties().getPartitionKeyDefinition().getPaths().get(0);
            String pkField = pkPath.startsWith("/") ? pkPath.substring(1) : pkPath;

            String pkValue = auction.getId();
            if ("sellerId".equals(pkField)) {
                pkValue = auction.getSellerId() == null ? auction.getId() : auction.getSellerId();
            } else if ("legoSetId".equals(pkField)) {
                pkValue = auction.getLegoSetId() == null ? auction.getId() : auction.getLegoSetId();
            } else if ("id".equals(pkField)) {
                pkValue = auction.getId();
            } else {
                try {
                    var f = AuctionDAO.class.getDeclaredField(pkField);
                    f.setAccessible(true);
                    Object val = f.get(auction);
                    if (val != null) pkValue = val.toString();
                } catch (Exception ignore) { }
            }

            LOG.info("addBidToAuction: auctionId=" + auctionId + " using partitionKeyPath=" + pkPath + " pkValue=" + pkValue);
            auctions.patchItem(auctionId, new PartitionKey(pkValue),
                    CosmosPatchOperations.create().add("/bids/-", bid), AuctionDAO.class);
        } catch (Exception e) {
            LOG.severe("addBidToAuction: failed to patch auctionId=" + auctionId + " err=" + e.getMessage());
            throw e;
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

    public void close() {
        client.close();
    }
}