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
    private String[] tags;

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

    // adiciona setter compatível com CosmosDBLayer
    public void setTags(String[] tags) {
        this.tags = tags;
    }

    // overload útil se preferires List<String>
    public void setTags(java.util.List<String> tagsList) {
        if (tagsList == null) {
            this.tags = null;
        } else {
            this.tags = tagsList.toArray(new String[0]);
        }
    }
}