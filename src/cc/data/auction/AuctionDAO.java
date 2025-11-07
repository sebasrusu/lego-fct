package cc.data.auction;

import cc.data.bid.Bid;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AuctionDAO {
    private String _rid;
    private String _ts;
    private String id;
    private String legoSetId;
    private String sellerId;
    private double basePrice;
    private long closeDate;
    private List<Bid> bids = new ArrayList<>();
    private boolean closed = false;

    public AuctionDAO(Auction a) {
        if (a == null) return;
        this.id = a.getId();
        this.legoSetId = a.getLegoSetId();
        this.sellerId = a.getSellerId();
        this.basePrice = a.getBasePrice();
        this.closeDate = a.getCloseDate();
        this.bids = a.getBids() == null ? new ArrayList<>() : a.getBids();
        this.closed = a.isClosed();
    }

    public Auction toAuction() {
        return new Auction(id, legoSetId, sellerId, basePrice, closeDate, bids, closed);
    }
}