package cc.functions;

import cc.data.auction.AuctionDAO;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.util.logging.Logger;
import cc.db.CosmosDBLayer;

public class CloseAuctionsFunction {

    @FunctionName("CloseAuctions")
    public void runCloseAuctions(
            @TimerTrigger(name = "closeAuctionsWithLayerTimer", schedule = "0 */5 * * * *") String timerInfo,
            final ExecutionContext context){
        Logger logger = context.getLogger();
        logger.info("=== CloseAuctions function triggered (Using CosmosDBLayer) ===");

        try {
            CosmosDBLayer db = CosmosDBLayer.getInstance();
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

    @FunctionName("GarbageCollection")
    public void runGarbageCollection(
            @TimerTrigger(name = "garbageCollectionTimer", schedule = "0 */4 * * * *") String timerInfo, // Executa a cada 4 minutos
            final ExecutionContext context) {
        
        Logger logger = context.getLogger();
        logger.info("=== GARBAGE COLLECTION: Limpando sessões expiradas ===");

        try {
            CosmosDBLayer db = CosmosDBLayer.getInstance();
            int deletedCount = db.deleteExpiredSessions();
            logger.info(String.format("Garbage Collection concluído. %d sessões expiradas foram removidas.", deletedCount));
        } catch (Exception e) {
            logger.severe("Erro durante o Garbage Collection: " + e.getMessage());
        }
    }
}
