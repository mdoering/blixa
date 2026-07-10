package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.child.DistributionMapper;
import org.catalogueoflife.editor.child.EstimateMapper;
import org.catalogueoflife.editor.child.MediaMapper;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.PropertyMapper;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.VernacularMapper;
import org.catalogueoflife.editor.child.dto.DistributionResponse;
import org.catalogueoflife.editor.child.dto.EstimateResponse;
import org.catalogueoflife.editor.child.dto.MediaResponse;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.child.dto.PropertyResponse;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.child.dto.VernacularResponse;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.springframework.stereotype.Component;

// Builds the 7 taxon/name child-entity files: TypeMaterial.tsv and NameRelation.tsv key off the
// NAME (ColdpTerm.nameID = the usage id, since our model collapses name+taxon into one row -- see
// NameUsage's class doc), the other five (Distribution/VernacularName/Media/SpeciesEstimate/
// TaxonProperty) key off the TAXON (ColdpTerm.taxonID = the usage id) -- exactly which FK column a
// file uses is fixed by ColdpTerm.RESOURCES, not a per-writer choice. Each file is written, like
// Author.tsv (AuthorColdpWriter), only when its findByProject list is non-empty: skipped (not
// header-only) otherwise, so ColdpReader.hasSchema reads false for entity types a project doesn't
// use at all.
@Component
public class ChildColdpWriter {

  private final TypeMaterialMapper typeMaterials;
  private final DistributionMapper distributions;
  private final VernacularMapper vernaculars;
  private final MediaMapper media;
  private final EstimateMapper estimates;
  private final NameRelationMapper nameRelations;
  private final PropertyMapper properties;

  public ChildColdpWriter(TypeMaterialMapper typeMaterials, DistributionMapper distributions,
      VernacularMapper vernaculars, MediaMapper media, EstimateMapper estimates,
      NameRelationMapper nameRelations, PropertyMapper properties) {
    this.typeMaterials = typeMaterials;
    this.distributions = distributions;
    this.vernaculars = vernaculars;
    this.media = media;
    this.estimates = estimates;
    this.nameRelations = nameRelations;
    this.properties = properties;
  }

  /** Writes each of the 7 child files under {@code dir}, skipping any whose project list is empty. */
  public void write(Path dir, int projectId) throws IOException {
    writeIfNotEmpty(dir, ColdpTerm.TypeMaterial,
        typeMaterials.findByProject(projectId), ChildColdpWriter::typeMaterialRow);
    writeIfNotEmpty(dir, ColdpTerm.Distribution,
        distributions.findByProject(projectId), ChildColdpWriter::distributionRow);
    writeIfNotEmpty(dir, ColdpTerm.VernacularName,
        vernaculars.findByProject(projectId), ChildColdpWriter::vernacularRow);
    writeIfNotEmpty(dir, ColdpTerm.Media,
        media.findByProject(projectId), ChildColdpWriter::mediaRow);
    writeIfNotEmpty(dir, ColdpTerm.SpeciesEstimate,
        estimates.findByProject(projectId), ChildColdpWriter::estimateRow);
    writeIfNotEmpty(dir, ColdpTerm.NameRelation,
        nameRelations.findByProject(projectId), ChildColdpWriter::nameRelationRow);
    writeIfNotEmpty(dir, ColdpTerm.TaxonProperty,
        properties.findByProject(projectId), ChildColdpWriter::propertyRow);
  }

  private static <T> void writeIfNotEmpty(Path dir, ColdpTerm fileTerm, List<T> entities,
      java.util.function.Function<T, Map<ColdpTerm, String>> toRow) throws IOException {
    if (entities.isEmpty()) {
      return;
    }
    List<Map<ColdpTerm, String>> rows = entities.stream().map(toRow).toList();
    ColdpTsv.writeFile(dir, fileTerm, rows);
  }

  // nameID: TypeMaterial attaches to a NAME, not a taxon (see ColdpTerm.RESOURCES.get(TypeMaterial)).
  // occurrenceId has no ColdpTerm column on TypeMaterial (no occurrenceID term in that file).
  private static Map<ColdpTerm, String> typeMaterialRow(TypeMaterialResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, str(r.id()));
    row.put(ColdpTerm.nameID, str(r.usageId()));
    row.put(ColdpTerm.citation, r.citation());
    row.put(ColdpTerm.status, r.status());
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.country, r.country());
    row.put(ColdpTerm.locality, r.locality());
    row.put(ColdpTerm.latitude, str(r.latitude()));
    row.put(ColdpTerm.longitude, str(r.longitude()));
    row.put(ColdpTerm.sex, r.sex());
    row.put(ColdpTerm.date, r.date());
    row.put(ColdpTerm.collector, r.collector());
    row.put(ColdpTerm.institutionCode, r.institutionCode());
    row.put(ColdpTerm.catalogNumber, r.catalogNumber());
    row.put(ColdpTerm.link, r.link());
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  // taxonID: Distribution has no ID column of its own in ColDP (see ColdpTerm.RESOURCES).
  private static Map<ColdpTerm, String> distributionRow(DistributionResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.taxonID, str(r.usageId()));
    row.put(ColdpTerm.area, r.area());
    row.put(ColdpTerm.areaID, r.areaId());
    row.put(ColdpTerm.gazetteer, r.gazetteer());
    row.put(ColdpTerm.establishmentMeans, r.establishmentMeans());
    row.put(ColdpTerm.threatStatus, r.threatStatus());
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  private static Map<ColdpTerm, String> vernacularRow(VernacularResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.taxonID, str(r.usageId()));
    row.put(ColdpTerm.name, r.name());
    row.put(ColdpTerm.language, r.language());
    row.put(ColdpTerm.country, r.country());
    row.put(ColdpTerm.sex, r.sex());
    row.put(ColdpTerm.preferred, str(r.preferred()));
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  private static Map<ColdpTerm, String> mediaRow(MediaResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.taxonID, str(r.usageId()));
    row.put(ColdpTerm.url, r.url());
    row.put(ColdpTerm.type, r.type());
    row.put(ColdpTerm.title, r.title());
    row.put(ColdpTerm.creator, r.creator());
    row.put(ColdpTerm.license, r.license());
    row.put(ColdpTerm.link, r.link());
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  private static Map<ColdpTerm, String> estimateRow(EstimateResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.taxonID, str(r.usageId()));
    row.put(ColdpTerm.estimate, str(r.estimate()));
    row.put(ColdpTerm.type, r.type());
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  // nameID/relatedNameID: NameRelation attaches two NAMES, not taxa (see ColdpTerm.RESOURCES).
  // relatedName (the joined display label from NameRelationMapper.SELECT) has no ColdpTerm column.
  private static Map<ColdpTerm, String> nameRelationRow(NameRelationResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.nameID, str(r.usageId()));
    row.put(ColdpTerm.relatedNameID, str(r.relatedUsageId()));
    row.put(ColdpTerm.type, r.type());
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.page, r.page());
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  private static Map<ColdpTerm, String> propertyRow(PropertyResponse r) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.taxonID, str(r.usageId()));
    row.put(ColdpTerm.property, r.property());
    row.put(ColdpTerm.value, r.value());
    row.put(ColdpTerm.page, r.page());
    row.put(ColdpTerm.referenceID, str(r.referenceId()));
    row.put(ColdpTerm.remarks, r.remarks());
    return row;
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
