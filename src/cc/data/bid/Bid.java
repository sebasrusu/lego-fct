package cc.data.bid;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Bid {
    private String id;
    private String userId;
    private double amount;
    private long timestamp;
}