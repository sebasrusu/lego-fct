package cc.functions;

import cc.db.CosmosDBLayer;
import cc.data.auction.AuctionDAO;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.util.logging.Logger;

public class CloseAuctionsFunction {
    private static final Logger LOG = Logger.getLogger(CloseAuctionsFunction.class.getName());

    @FunctionName("CloseAuctions")
    public void run(
        @TimerTrigger(name = "timerInfo", schedule = "0 */1 * * * *") String timerInfo,
        final ExecutionContext context) {

        LOG.info("=== CloseAuctions function triggered (Using CosmosDBLayer) ===");
        CosmosDBLayer db = CosmosDBLayer.getInstance();

        try {
            for (AuctionDAO a : db.listExpiredAuctions()) {
                LOG.info("Closing auction: " + a.getId());
                db.updateAuction(a);
            }
        } catch (Exception e) {
            LOG.severe("Error in CloseAuctions function: " + e.getMessage());
        }
    }

    @FunctionName("GarbageCollection")
    public void runGarbageCollection(
            @TimerTrigger(name = "garbageCollectionTimer", schedule = "0 */1 * * * *") String timerInfo, // Executa a cada 4 minutos
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
