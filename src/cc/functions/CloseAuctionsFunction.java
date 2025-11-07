package cc.functions;

import cc.data.auction.AuctionDAO;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.util.logging.Logger;
import cc.db.CosmosDBLayer;

public class CloseAuctionsFunction {

    // single function to close expired auctions using CosmosDBLayer
    @FunctionName("CloseAuctions")
    public void runCloseAuctions(
            @TimerTrigger(name = "timer", schedule = "0 */5 * * * *") String timerInfo,
            final ExecutionContext context){
        Logger logger = context.getLogger();
        logger.info("CloseAuctions function triggered: " + timerInfo);

        try {
            CosmosDBLayer db = CosmosDBLayer.getInstance();
            var expiredAuctions = db.listExpiredAuctions();
            for (AuctionDAO auction : expiredAuctions) {
                logger.info("Closing auction: " + auction.getId());
                auction.setClosed(true);
                // persist change
                db.updateAuction(auction);
            }
        } catch (Exception e) {
            logger.severe("Error in CloseAuctions function: " + e.getMessage());
        }
    }
    
}
