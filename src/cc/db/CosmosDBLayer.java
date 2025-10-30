package cc.db;

import cc.data.auction.AuctionDAO;
import cc.data.bid.Bid;
import cc.data.comment.CommentDAO;
import cc.data.lego.LegoSetDAO;
import cc.data.user.UserDAO;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.*;
import com.azure.cosmos.util.CosmosPagedIterable;

import java.util.List;

public class CosmosDBLayer {
	private static final String CONNECTION_URL = System.getenv("DB_URL");
	private static final String DB_KEY = System.getenv("DB_KEY");
	private static final String DB_NAME = System.getenv("DB_NAME");

	private static CosmosDBLayer instance;

	private CosmosClient client;
	private CosmosDatabase db;
	private CosmosContainer users;
	private CosmosContainer legosets;
	private CosmosContainer comments;
	private CosmosContainer auctions;

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
		db = client.getDatabase(DB_NAME);
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
		users.deleteItem(id, new PartitionKey(id), new CosmosItemRequestOptions());
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
		return legosets.queryItems("SELECT * FROM c WHERE c.id='" + id + "'", null, LegoSetDAO.class).iterator()
				.next();
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

	public CosmosPagedIterable<LegoSetDAO> listMostRecentLegoSets() {
		init();
		return legosets.queryItems("SELECT * FROM c ORDER BY c.creationTime DESC OFFSET 0 LIMIT 20", null,
				LegoSetDAO.class);
	}

	// --- Comment Methods ---
	public CommentDAO createComment(CommentDAO comment) {
		init();
		return comments.createItem(comment).getItem();
	}

	public CosmosPagedIterable<CommentDAO> listCommentsByLegoSet(String legoSetId) {
		init();
		return comments.queryItems("SELECT * FROM c WHERE c.legoSetId = '" + legoSetId + "'", null, CommentDAO.class);
	}

	// --- Auction Methods ---
	public AuctionDAO createAuction(AuctionDAO auction) {
		init();
		return auctions.createItem(auction).getItem();
	}

	public CosmosPagedIterable<AuctionDAO> listAuctions() {
		init();
		return auctions.queryItems("SELECT * FROM c WHERE c.closeDate > " + System.currentTimeMillis(), null,
				AuctionDAO.class);
	}

	public AuctionDAO getAuction(String auctionId) {
		init();
		return auctions.queryItems("SELECT * FROM c WHERE c.id = '" + auctionId + "'", null, AuctionDAO.class)
				.iterator().next();
	}

	public void addBidToAuction(String auctionId, Bid bid) {
		init();
		auctions.patchItem(auctionId, new PartitionKey(auctionId),
				CosmosPatchOperations.create().add("/bids/-", bid), AuctionDAO.class);
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