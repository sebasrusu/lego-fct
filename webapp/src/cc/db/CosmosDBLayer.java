package cc.db;

import cc.data.auction.AuctionDAO;
import cc.data.bid.Bid;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
import cc.data.auth.Session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CosmosDBLayer {

    private static final Logger LOG = Logger.getLogger(CosmosDBLayer.class.getName());

    // singleton instance
    private static CosmosDBLayer instance;

    // mantemos estes nomes para reutilizar as envs DB_URL e DB_NAME
    private static final String CONNECTION_URL;
    private static final String DB_KEY; // já não é usado, mas mantemos para compatibilidade
    private static final String DB_NAME;

    static {
        String url = System.getenv("DB_URL");
        String dbName = System.getenv("DB_NAME");
        String key = System.getenv("DB_KEY"); // ignorado para Mongo

        CONNECTION_URL = (url != null && !url.isBlank())
                ? url
                : "mongodb://admin:senhaSegura123@localhost:27017/ccdb?authSource=admin";
        DB_NAME = (dbName != null && !dbName.isBlank()) ? dbName : "ccdb";
        DB_KEY = (key != null) ? key : "";

        LOG.info("CosmosDBLayer(Mongo): DB_URL=" + CONNECTION_URL + " DB_NAME=" + DB_NAME);
    }

    // Mongo client & collections
    private MongoClient client;
    private MongoDatabase db;

    private MongoCollection<Document> users;
    private MongoCollection<Document> legosets;
    private MongoCollection<Document> comments;
    private MongoCollection<Document> auctions;
    private MongoCollection<Document> sessions;
    private MongoCollection<Document> legoDescriptions;

    private final ObjectMapper mapper;

    private CosmosDBLayer() {
        mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static synchronized CosmosDBLayer getInstance() {
        if (instance == null) {
            instance = new CosmosDBLayer();
            instance.init();
        }
        return instance;
    }

    private synchronized void init() {
        if (db != null) return;

        if (CONNECTION_URL == null || CONNECTION_URL.isBlank()) {
            LOG.severe("CosmosDBLayer(Mongo).init: DB_URL not provided");
            return;
        }

        LOG.info("CosmosDBLayer(Mongo).init: connecting to " + CONNECTION_URL);
        client = MongoClients.create(CONNECTION_URL);
        db = client.getDatabase(DB_NAME);

        users = db.getCollection("users");
        legosets = db.getCollection("legosets");
        comments = db.getCollection("comments");
        auctions = db.getCollection("auctions");
        sessions = db.getCollection("sessions");
        legoDescriptions = db.getCollection("lego_descriptions");

        // índices úteis (opcional)
        users.createIndex(new Document("nickname", 1));
        legosets.createIndex(new Document("ownerId", 1));
        comments.createIndex(new Document("legoSetId", 1));
        comments.createIndex(new Document("userId", 1));
        auctions.createIndex(new Document("sellerId", 1));
        auctions.createIndex(new Document("legoSetId", 1));
    }

    // ==================== Helpers de conversão ====================

    private <T> Document toDoc(T obj) {
        if (obj == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.convertValue(obj, Map.class);
        return new Document(map);
    }

    private <T> T fromDoc(Document doc, Class<T> clazz) {
        if (doc == null) return null;
        // remover _id para não chocar com o campo id do DAO
        Object _id = doc.get("_id");
        if (_id != null && !doc.containsKey("id")) {
            doc.put("id", _id.toString());
        }
        return mapper.convertValue(doc, clazz);
    }

    private <T> List<T> toList(FindIterable<Document> docs, Class<T> clazz) {
        List<T> out = new ArrayList<>();
        for (Document d : docs) {
            out.add(fromDoc(d, clazz));
        }
        return out;
    }

    // ==================== Users ====================

    public UserDAO createUser(UserDAO user) {
        init();
        Document doc = toDoc(user);
        if (user.getId() != null) {
            doc.put("_id", user.getId());
        }
        users.insertOne(doc);
        return fromDoc(doc, UserDAO.class);
    }

    public UserDAO getUser(String id) {
        init();
        Document doc = users.find(Filters.eq("_id", id)).first();
        if (doc == null) {
            doc = users.find(Filters.eq("id", id)).first();
        }
        return fromDoc(doc, UserDAO.class);
    }

    // antes era CosmosPagedIterable<UserDAO>; Iterable chega para os resources
    public Iterable<UserDAO> listUsers() {
        init();
        return toList(users.find(), UserDAO.class);
    }

    public AuctionDAO updateAuction(AuctionDAO auction) {
        init();
        if (auction == null || auction.getId() == null) {
            LOG.warning("updateAuction: auction or auction.id is null");
            return null;
        }

        Document doc = toDoc(auction);
        doc.put("_id", auction.getId());

        auctions.replaceOne(
                Filters.eq("_id", auction.getId()),
                doc,
                new ReplaceOptions().upsert(true)
        );

        return auction;
    }

    public UserDAO updateUser(UserDAO user) {
        init();
        Document doc = toDoc(user);
        String id = user.getId();
        if (id != null) {
            doc.put("_id", id);
        }
        users.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
        return user;
    }

    public void deleteUser(String id) {
        init();
        String deletedUserId = "deleted-user";

        // reatribuir tudo ao utilizador "deleted-user"
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

        users.deleteOne(Filters.eq("_id", id));
    }

    public void createDeletedUserIfNotExists() {
        init();
        String deletedUserId = "deleted-user";
        UserDAO u = getUser(deletedUserId);
        if (u == null) {
            UserDAO deletedUser = new UserDAO();
            deletedUser.setId(deletedUserId);
            deletedUser.setName("Deleted User");
            deletedUser.setPwd("");
            deletedUser.setPhotoId(null);
            deletedUser.setLegoIds(new String[0]);
            createUser(deletedUser);
        }
    }

    public Iterable<CommentDAO> listCommentsByUser(String userId) {
        init();
        return toList(
                comments.find(Filters.eq("userId", userId)),
                CommentDAO.class
        );
    }

    public Iterable<AuctionDAO> listAuctionsOfUser(String userId) {
        init();
        return toList(
                auctions.find(Filters.eq("sellerId", userId)),
                AuctionDAO.class
        );
    }

    public UserDAO findUserByNickname(String name) {
        init();
        Document doc = users.find(Filters.eq("nickname", name)).first();
        return fromDoc(doc, UserDAO.class);
    }

    // ==================== LegoSets ====================

    public LegoSetDAO createLegoSet(LegoSetDAO ls) {
        init();
        Document doc = toDoc(ls);
        if (ls.getId() != null) {
            doc.put("_id", ls.getId());
        }
        legosets.insertOne(doc);
        return ls;
    }

    public LegoSetDAO getLegoSet(String id) {
        init();
        Document doc = legosets.find(Filters.eq("_id", id)).first();
        if (doc == null) {
            doc = legosets.find(Filters.eq("id", id)).first();
        }
        return fromDoc(doc, LegoSetDAO.class);
    }

    public Iterable<LegoSetDAO> listLegoSets() {
        init();
        return toList(legosets.find(), LegoSetDAO.class);
    }

    public LegoSetDAO updateLegoSet(LegoSetDAO ls) {
        init();
        Document doc = toDoc(ls);
        String id = ls.getId();
        if (id != null) {
            doc.put("_id", id);
        }
        legosets.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
        return ls;
    }

    public void deleteLegoSet(String id) {
        init();
        legosets.deleteOne(Filters.eq("_id", id));
    }

    public Iterable<LegoSetDAO> listLegoSetsOfUser(String userId) {
        init();
        return toList(
                legosets.find(Filters.eq("ownerId", userId)),
                LegoSetDAO.class
        );
    }

    public Iterable<LegoSetDAO> listMostRecentLegoSets(int offset, int limit) {
        init();
        // se não houver createdAt, usamos _id como fallback
        FindIterable<Document> it = legosets
                .find()
                .sort(Sorts.descending("createdAt", "_id"))
                .skip(offset)
                .limit(limit);
        return toList(it, LegoSetDAO.class);
    }

    // ==================== Comments ====================

    public CommentDAO createComment(CommentDAO dao) {
        init();
        Document doc = toDoc(dao);
        if (dao.getId() != null) {
            doc.put("_id", dao.getId());
        }
        comments.insertOne(doc);
        return dao;
    }

    public List<CommentDAO> listCommentsByLegoSet(String legoSetId) {
        init();
        if (legoSetId == null || legoSetId.isBlank()) return Collections.emptyList();
        FindIterable<Document> it = comments.find(Filters.eq("legoSetId", legoSetId));
        return toList(it, CommentDAO.class);
    }

    public CommentDAO updateComment(CommentDAO comment) {
        init();
        Document doc = toDoc(comment);
        String id = comment.getId();
        if (id != null) {
            doc.put("_id", id);
        }
        comments.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
        return comment;
    }

    // ==================== Auctions ====================

    public AuctionDAO createAuction(AuctionDAO auction) {
        init();
        Document doc = toDoc(auction);
        if (auction.getId() != null) {
            doc.put("_id", auction.getId());
        }
        auctions.replaceOne(
                Filters.eq("_id", auction.getId()),
                doc,
                new ReplaceOptions().upsert(true)
        );
        return auction;
    }

    public Iterable<AuctionDAO> listAuctions(int offset, int limit) {
        init();
        FindIterable<Document> it = auctions
                .find()
                .sort(Sorts.descending("closeDate", "_id"))
                .skip(offset)
                .limit(limit);
        return toList(it, AuctionDAO.class);
    }

    // open: closed != true AND closeDate > now
    public Iterable<AuctionDAO> listOpenAuctions(int offset, int limit) {
        init();
        long now = System.currentTimeMillis();
        FindIterable<Document> it = auctions
                .find(Filters.and(
                        Filters.or(
                                Filters.ne("closed", true),
                                Filters.exists("closed", false)
                        ),
                        Filters.gt("closeDate", now)
                ))
                .sort(Sorts.descending("closeDate", "_id"))
                .skip(offset)
                .limit(limit);
        return toList(it, AuctionDAO.class);
    }

    // closed: closed == true OR closeDate <= now
    public Iterable<AuctionDAO> listClosedAuctions(int offset, int limit) {
        init();
        long now = System.currentTimeMillis();
        FindIterable<Document> it = auctions
                .find(Filters.or(
                        Filters.eq("closed", true),
                        Filters.lte("closeDate", now)
                ))
                .sort(Sorts.descending("closeDate", "_id"))
                .skip(offset)
                .limit(limit);
        return toList(it, AuctionDAO.class);
    }

    public AuctionDAO getAuction(String id) {
        init();
        Document doc = auctions.find(Filters.eq("_id", id)).first();
        if (doc == null) {
            doc = auctions.find(Filters.eq("id", id)).first();
        }
        return fromDoc(doc, AuctionDAO.class);
    }

    public void addBidToAuction(String auctionId, Bid bid) {
        init();
        Document doc = auctions.find(Filters.eq("_id", auctionId)).first();
        if (doc == null) {
            doc = auctions.find(Filters.eq("id", auctionId)).first();
        }
        if (doc == null) {
            LOG.warning("addBidToAuction: auction not found id=" + auctionId);
            return;
        }

        AuctionDAO auction = fromDoc(doc, AuctionDAO.class);
        List<Bid> bids = auction.getBids();
        if (bids == null) bids = new ArrayList<>();
        bids.add(bid);
        auction.setBids(bids);

        Document newDoc = toDoc(auction);
        newDoc.put("_id", auctionId);
        auctions.replaceOne(Filters.eq("_id", auctionId), newDoc, new ReplaceOptions().upsert(true));
    }

    // leilões expirados (para a função de fechar auctions, se usares)
    public Iterable<AuctionDAO> listExpiredAuctions() {
        init();
        long now = System.currentTimeMillis();
        FindIterable<Document> it = auctions.find(Filters.and(
                Filters.lte("closeDate", now),
                Filters.ne("closed", true)
        ));
        return toList(it, AuctionDAO.class);
    }

    public Iterable<AuctionDAO> searchAuctionForLegoSet(String legoSetId) {
        init();
        long now = System.currentTimeMillis();
        FindIterable<Document> it = auctions.find(Filters.and(
                Filters.eq("legoSetId", legoSetId),
                Filters.gt("closeDate", now)
        ));
        return toList(it, AuctionDAO.class);
    }

    // ==================== Métricas (stubs – não usados pelos resources) ====================

    public long countNewUsersInLast3Minutes() {
        // se quiseres, podes implementar com createdAt; para o TP2, 0 chega
        return 0;
    }

    public long countNewAuctionsInLast3Minutes() {
        return 0;
    }

    public long countNewBidsInLast3Minutes() {
        return 0;
    }

    public int deleteExpiredSessions() {
        // se vieres a usar sessões em Mongo, implementas aqui
        return 0;
    }

    public void close() {
        if (client != null) {
            client.close();
        }
    }

    // ==================== Lego descriptions ====================

    public void upsertLegoDescription(String legoSetId, String description, String[] tags) {
        init();
        if (legoDescriptions == null) return;

        Map<String, Object> map = new HashMap<>();
        map.put("_id", legoSetId);
        map.put("legoSetId", legoSetId);
        map.put("description", description);
        map.put("tags", tags != null ? Arrays.asList(tags) : Collections.emptyList());

        Document doc = new Document(map);
        legoDescriptions.replaceOne(
                Filters.eq("_id", legoSetId),
                doc,
                new ReplaceOptions().upsert(true)
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLegoDescription(String legoSetId) {
        init();
        if (legoDescriptions == null) return null;

        Document doc = legoDescriptions.find(Filters.eq("_id", legoSetId)).first();
        if (doc == null) {
            doc = legoDescriptions.find(Filters.eq("legoSetId", legoSetId)).first();
        }
        if (doc == null) return null;

        Map<String, Object> map = new HashMap<>(doc);
        map.remove("_id");
        return map;
    }
}
