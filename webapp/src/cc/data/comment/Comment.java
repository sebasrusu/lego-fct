package cc.data.comment;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Comment {
    private String id;
    private String legoSetId;
    private String userId;
    private String commentText;

    public Comment(String id, String legoSetId, String userId, String commentText) {
        this.id = id;
        this.legoSetId = legoSetId;
        this.userId = userId;
        this.commentText = commentText;
    }
}