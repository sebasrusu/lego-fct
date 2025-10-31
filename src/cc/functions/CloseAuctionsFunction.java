package cc.functions;

import cc.data.auction.AuctionDAO;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;

public class CloseAuctionsFunction {

    @FunctionName("CloseExpiredAuctions")
    public void run(
            @TimerTrigger(name = "timer", schedule = "0 */10 * * * *") String timerInfo,
            ExecutionContext context
    ) {
        context.getLogger().info("=== FECHAR LEILÕES EXPIRADOS ===");

        String url = System.getenv("DB_URL");
        String key = System.getenv("DB_KEY");
        String dbName = System.getenv("DB_NAME");

        CosmosClient client = new CosmosClientBuilder()
                .endpoint(url)
                .key(key)
                .buildClient();

        CosmosContainer auctions = client.getDatabase(dbName).getContainer("auctions");

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
}
