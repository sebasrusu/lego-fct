package cc.data.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class Session {
    private String sid;
    private String userId;
    private long createdAt;
}
