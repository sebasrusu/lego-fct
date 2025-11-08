package cc.data.comment;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CommentDAO {
    private String _rid;
    private String _ts;
    private String id;
    private String legoSetId;
    private String userId;
    private String commentText;

    public CommentDAO(Comment c) {
        this.id = c.getId();
        this.legoSetId = c.getLegoSetId();
        this.userId = c.getUserId();
        this.commentText = c.getCommentText();
    }

    public Comment toComment() {
        return new Comment(id, legoSetId, userId, commentText);
    }
}