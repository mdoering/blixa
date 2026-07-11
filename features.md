
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
upload a tsv/csv file with names into a selected, existing taxon
 a) plain names per line: parse the names and add them as accepted children to the insert taxon. Allow all names to be treated as synonyms of the target taxon
 b) texttree upload to a parent. includes both accepted and syns

## homotypic grouping
select a taxon and group the species/infraspecies homotypicly - see CLB backend

## reference imports
 - by DOI: query crossref
 - bibtex import
 - csl-json import

 ## reference consolidation
 - find DOIs for existing references