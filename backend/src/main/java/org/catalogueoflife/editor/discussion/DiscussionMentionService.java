package org.catalogueoflife.editor.discussion;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.discussion.dto.Mentions;
import org.catalogueoflife.editor.discussion.dto.Mentions.UserMention;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.stereotype.Service;

// Resolves inline mentions in discussion/comment markdown:
//   #<int>    -> a name_usage in the project (label = scientific name)
//   @<orcid>  -> a known user by ORCID       (label = display name)
//   @<username> -> a known user by username  (label = display name)
// `#Genus_species` (a name string) is intentionally NOT matched here -- fuzzy name resolution is
// deferred; only the numeric #id form is handled (which also drives the reverse-link table).
@Service
public class DiscussionMentionService {

  private static final Pattern USAGE_REF = Pattern.compile("#(\\d+)");
  // A @-token is an ORCID or a username: letters/digits then letters/digits/_/-. (ORCIDs are digits
  // and hyphens, so they match too.) Trailing punctuation like a period is excluded.
  private static final Pattern USER_REF = Pattern.compile("@([A-Za-z0-9][A-Za-z0-9_-]*)");
  private static final Pattern ORCID_FORMAT =
      Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dXx]");

  private final NameUsageMapper usages;
  private final AppUserMapper users;

  public DiscussionMentionService(NameUsageMapper usages, AppUserMapper users) {
    this.usages = usages;
    this.users = users;
  }

  // Distinct usage ids referenced by #<int> across the given texts that actually exist in the
  // project -- the set of reverse-links a discussion should have (see DiscussionLinkService).
  public Set<Integer> existingUsageRefs(int projectId, Collection<String> texts) {
    Set<Integer> ids = new LinkedHashSet<>();
    for (String t : texts) {
      if (t == null) continue;
      Matcher m = USAGE_REF.matcher(t);
      while (m.find()) {
        int id = Integer.parseInt(m.group(1));
        if (!ids.contains(id) && usages.findScientificName(projectId, id) != null) ids.add(id);
      }
    }
    return ids;
  }

  // Resolve #nameID -> scientific name and @orcid/@username -> user across the given texts.
  public Mentions resolve(int projectId, String... texts) {
    Map<String, String> usageLabels = new LinkedHashMap<>();
    Map<String, UserMention> userMentions = new LinkedHashMap<>();
    for (String t : texts) {
      if (t == null) continue;
      Matcher mu = USAGE_REF.matcher(t);
      while (mu.find()) {
        String key = mu.group(1);
        if (!usageLabels.containsKey(key)) {
          String name = usages.findScientificName(projectId, Integer.parseInt(key));
          if (name != null) usageLabels.put(key, name);
        }
      }
      Matcher mo = USER_REF.matcher(t);
      while (mo.find()) {
        String token = mo.group(1);
        if (!userMentions.containsKey(token)) {
          AppUser u = ORCID_FORMAT.matcher(token).matches()
              ? users.findByOrcid(token) : users.findByUsername(token);
          if (u != null) userMentions.put(token, new UserMention(displayLabel(u), u.getOrcid()));
        }
      }
    }
    return new Mentions(usageLabels, userMentions);
  }

  private static String displayLabel(AppUser u) {
    if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
    String gf = ((u.getGiven() == null ? "" : u.getGiven()) + " "
        + (u.getFamily() == null ? "" : u.getFamily())).trim();
    return gf.isBlank() ? u.getUsername() : gf;
  }
}
