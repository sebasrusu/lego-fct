package cc.data.lego;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LegoSet {
    private String id;
    private String name;
    private String codeNumber;
    private String description;
    private String photoId;
    private String ownerId;
}