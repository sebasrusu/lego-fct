package cc.data.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
	private String id;
	private String name;
	private String pwd;
	private String photoId;
	private String[] legoIds;
}