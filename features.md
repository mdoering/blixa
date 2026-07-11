
# business rules

## taxonomic status
The name usage status is an important vocabulary that defines many things:
 - only accepted names are shown in a tree and can carry associated taxon information (vernacular, distribution, properties, estimates, environments, geoRange)
 - synonyms must point to at elast one accepted taxon
 - no synonym chaining

### Status changes
acc->syn: a new accepted name must be selected. All tax info must be migrated. by default to the new accepted, but do ask a user
syn->acc: a promotion

treat misapplied names the same as synonyms

## rules for soft validation
 - year of combination of a genus must be equal to or preceed that of the species
 - combination authorship year should be +/- the same as the published in reference year. Sometimes it differs by a year or 2, but thats rare

##  manage issues
Users should be able to regenerate issues on demand, e.g. when new code was deployed.
Reviewed issues should be accepted or rejected and the reviewer & datetime tracked. Probably a state=open,accepted (work to be done), rejected (to be ignored) and done.



# tools

## bulk name inserts
Insert a list of names into a selected, existing taxon, either as accepted children or as a synonymy of the target.
Input via a **text-field (paste)** OR a **plain-text / TSV / TextTree file upload** — same parsing either way:
 a) plain names per line: parse the names and add them as accepted children to the insert taxon. Allow all names to be treated as synonyms of the target taxon instead.
 b) texttree (indented) input to a parent: includes both accepted and syns, preserving the hierarchy.
 c) the paste text-field is the low-friction path (no file needed); the file upload is for larger sets.

## direct CLB import (selected taxa)
Pull selected taxa straight from ChecklistBank into a project via the CLB API (no ColDP file, no whole-dataset import):
 - a whole genus with all its species and synonyms (the subtree)
 - a single species with all its associated info: synonyms, vernacular names, distributions, type material, references, etc.
Insert under a chosen target taxon. Should reuse the ColDP import/merge machinery for id handling, name-matching and the supervised-merge review (so a re-pull reconciles rather than duplicates). CLB source ids carried as `col:`/`<scope>:` CURIEs.

## homotypic grouping
select a taxon and group the species/infraspecies homotypicly - see CLB backend

## reference imports
 - by DOI: query crossref
 - bibtex import
 - csl-json import

 ## reference consolidation
 - find DOIs for existing references