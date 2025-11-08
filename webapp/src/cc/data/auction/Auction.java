package cc.data.auction;

import cc.data.bid.Bid;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Auction {
    private String id;
    private String legoSetId;
    private String sellerId;
    private double basePrice;
    private long closeDate;
    private List<Bid> bids;
    private boolean closed;

    // aliases and flexible parsing for incoming JSON
    @JsonSetter("closeDate")
    public void setCloseDateFrom(Object v) {
        setCloseDateFlexible(v);
    }

    @JsonSetter("endDate")
    public void setEndDateFrom(Object v) {
        setCloseDateFlexible(v);
    }

    private void setCloseDateFlexible(Object v) {
        if (v == null) return;
        try {
            if (v instanceof Number) {
                this.closeDate = ((Number) v).longValue();
                return;
            }
            String s = v.toString();
            try {
                this.closeDate = Long.parseLong(s);
                return;
            } catch (NumberFormatException ignored) {
            }
            try {
                this.closeDate = Instant.parse(s).toEpochMilli();
                return;
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    // alias for startingPrice -> basePrice
    @JsonSetter("startingPrice")
    public void setStartingPriceFrom(Object v) {
        if (v == null) return;
        try {
            if (v instanceof Number) {
                this.basePrice = ((Number) v).doubleValue();
                return;
            }
            this.basePrice = Double.parseDouble(v.toString());
        } catch (Exception ignored) {
        }
    }

    // alias for seller -> sellerId
    @JsonSetter("seller")
    public void setSellerAlias(String s) {
        if (s == null) return;
        this.sellerId = s;
    }
}