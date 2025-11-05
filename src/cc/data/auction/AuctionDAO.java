package cc.data.auction;

import cc.data.bid.Bid;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.Instant;

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
    private List<Bid> bids;
    private boolean closed;

    // aliases and flexible parsing for incoming JSON stored in DAO
    @JsonSetter("closeDate")
    public void setCloseDateFrom(Object v) { setCloseDateFlexible(v); }

    @JsonSetter("endDate")
    public void setEndDateFrom(Object v) { setCloseDateFlexible(v); }

    private void setCloseDateFlexible(Object v) {
        if (v == null) return;
        try {
            if (v instanceof Number) { this.closeDate = ((Number) v).longValue(); return; }
            String s = v.toString();
            try { this.closeDate = Long.parseLong(s); return; } catch (NumberFormatException ignored) {}
            try { this.closeDate = Instant.parse(s).toEpochMilli(); return; } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    @JsonSetter("startingPrice")
    public void setStartingPriceFrom(Object v) {
        if (v == null) return;
        try {
            if (v instanceof Number) { this.basePrice = ((Number) v).doubleValue(); return; }
            this.basePrice = Double.parseDouble(v.toString());
        } catch (Exception ignored) {}
    }

    @JsonSetter("seller")
    public void setSellerAlias(String s) { if (s != null) this.sellerId = s; }

    public AuctionDAO(Auction a) {
        this.id = a.getId();
        this.legoSetId = a.getLegoSetId();
        this.sellerId = a.getSellerId();
        this.basePrice = a.getBasePrice();
        this.closeDate = a.getCloseDate();
        this.bids = (a.getBids() != null) ? a.getBids() : new ArrayList<>();
        this.closed = false;
    }

    public Auction toAuction() {
        return new Auction(id, legoSetId, sellerId, basePrice, closeDate, bids, closed);
    }
}