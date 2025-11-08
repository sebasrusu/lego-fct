package cc.data.lego;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class LegoSetDAO {
    private String _rid;
    private String _ts;
    private String id;
    private String name;
    private String codeNumber;
    private String description;
    private String photoId;
    private String ownerId;
    private long creationTime;

    public LegoSetDAO(LegoSet ls) {
        this.creationTime = System.currentTimeMillis();
        this.id = ls.getId();
        this.name = ls.getName();
        this.codeNumber = ls.getCodeNumber();
        this.description = ls.getDescription();
        this.photoId = ls.getPhotoId();
        this.ownerId = ls.getOwnerId();
    }

    public LegoSet toLegoSet() {
        return new LegoSet(id, name, codeNumber, description, photoId, ownerId);
    }
}