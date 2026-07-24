package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// Grammatical gender belongs to the genus and gender agreement only to a bi/trinomial (see the name
// form's rank-gating). This flags the mismatches -- gender set on a non-genus, or genderAgreement set
// on a name with no specific epithet (CLB GENDER_AGREEMENT_NOT_APPLICABLE) -- which the form prevents
// but a ColDP import can still carry in.
@Component
public class GenderNotApplicableRule implements ValidationRule {

  @Override
  public String key() {
    return "gender_not_applicable";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    boolean genderOffGenus = u.getGender() != null && !"genus".equalsIgnoreCase(u.getRank());
    boolean agreementWithoutEpithet = Boolean.TRUE.equals(u.getGenderAgreement())
        && (u.getSpecificEpithet() == null || u.getSpecificEpithet().isBlank());
    if (genderOffGenus) {
      return Optional.of(new Finding(key(), severity(),
          "gender is set on a '" + u.getRank() + "' -- it belongs to the genus", null));
    }
    if (agreementWithoutEpithet) {
      return Optional.of(new Finding(key(), severity(),
          "gender agreement is set on a name without a specific epithet -- it applies to species and below",
          null));
    }
    return Optional.empty();
  }
}
