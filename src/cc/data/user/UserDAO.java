package cc.data.user;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserDAO {
	private String _rid;
	private String _ts;
	private String id;
	private String name;
	private String pwd;
	private String photoId;
	private String[] legoIds;

	public UserDAO(User u) {
		this.id = u.getId();
		this.name = u.getName();
		this.pwd = u.getPwd();
		this.photoId = u.getPhotoId();
		this.legoIds = u.getLegoIds();
	}

	public User toUser() {
		return new User(id, name, pwd, photoId, legoIds);
	}
}