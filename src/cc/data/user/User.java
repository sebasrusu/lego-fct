package cc.data.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
	private String id;
	private String name;
	private String nickname;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String pwd;

	private String photoId;
	private String[] legoIds;
}