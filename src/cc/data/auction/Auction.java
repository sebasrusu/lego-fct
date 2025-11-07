package cc.data.auction;

import cc.data.bid.Bid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}