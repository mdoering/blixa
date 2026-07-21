package org.catalogueoflife.editor.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppUserMapper {

  @Insert("""
      INSERT INTO app_user (orcid, username, email, display_name, given, family, password_hash,
                            admin, state)
      VALUES (#{orcid}, #{username}, #{email}, #{displayName}, #{given}, #{family}, #{passwordHash},
              #{admin}, COALESCE(#{state}, 'ACTIVE'))
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(AppUser u);

  @Select("SELECT * FROM app_user WHERE id = #{id}")
  AppUser findById(int id);

  @Select("SELECT * FROM app_user WHERE username = #{username}")
  AppUser findByUsername(String username);

  @Select("SELECT * FROM app_user WHERE orcid = #{orcid}")
  AppUser findByOrcid(String orcid);

  // Resolve the app user behind a login principal: a local login's principal is the username; an
  // ORCID login's principal is the ORCID iD (OrcidUserService keys the principal on "sub"). Matching
  // on either keeps an ORCID account resolvable after the user picks a custom username. A username
  // match wins if the name somehow matches one row's username and another row's orcid.
  @Select("""
      SELECT * FROM app_user WHERE username = #{name} OR orcid = #{name}
      ORDER BY (username = #{name}) DESC LIMIT 1
      """)
  AppUser findByUsernameOrOrcid(String name);

  @Select("SELECT * FROM app_user ORDER BY (state = 'PENDING') DESC, username")
  java.util.List<AppUser> findAll();

  @Update("""
      UPDATE app_user
      SET orcid = #{orcid}, username = #{username}, email = #{email},
          display_name = #{displayName}, given = #{given}, family = #{family},
          password_hash = #{passwordHash}, admin = #{admin}, state = #{state}, updated_at = now()
      WHERE id = #{id}
      """)
  void update(AppUser u);
}
