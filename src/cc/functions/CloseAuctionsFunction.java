package cc.functions;

import cc.data.auction.AuctionDAO;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import java.util.logging.Logger;
import cc.db.CosmosDBLayer;

public class CloseAuctionsFunction {

    @FunctionName("CloseExpiredAuctions")
    public void run(
            @TimerTrigger(name = "timer", schedule = "0 */10 * * * *") String timerInfo,
            ExecutionContext context
    ) {
        context.getLogger().info("=== FECHAR LEILÕES EXPIRADOS ===");

        String url = System.getenv("COSMOSDB_URL");
        String key = System.getenv("COSMOSDB_KEY");
        String dbName = System.getenv("COSMOSDB_DATABASE");

        CosmosClient client = new CosmosClientBuilder()
                .endpoint(url)
                .key(key)
                .buildClient();

        CosmosContainer auctions = client.getDatabase(dbName).getContainer("auction");



        long now = System.currentTimeMillis();
        String query = "SELECT * FROM c WHERE c.closeDate < " + now;

        for (var item : auctions.queryItems(query, new CosmosQueryRequestOptions(), AuctionDAO.class)) {
            context.getLogger().info("Leilão fechado: " + item.getId());
            // adicionar campo "closed" e fazer update
            item.setClosed(true);
            auctions.upsertItem(item);

        }

        client.close();
    }

        @FunctionName("CloseAuctions")
    public void runCloseAuctions(
            @TimerTrigger(name = "timer", schedule = "0 */5 * * * *") String timerInfo,
            final ExecutionContext context){
        Logger logger = context.getLogger();
        logger.info("CloseAuctions function triggered: " + timerInfo);

        CosmosDBLayer db = null;
        try {
            db = CosmosDBLayer.getInstance();
            var expiredAuctions = db.listExpiredAuctions();
            for (AuctionDAO auction : expiredAuctions) {
                logger.info("Closing auction: " + auction.getId());
                auction.setClosed(true);
                db.updateAuction(auction);
            }
        } catch (Exception e) {
            logger.severe("Error in CloseAuctions function: " + e.getMessage());
        }
    }
    
}
