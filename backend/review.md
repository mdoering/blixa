# java
## identifiers
I think we can use plain ints instead of longs for identifiers.
It would be nice to have a identifier sequence starting from 1 in each project,
so we would need to use compound keys with projectId?
 
## Project
 - code should be using the enum?
 - remove doi
 - license should be an enum and only allow the 2 open licenses CC0 and CC-BY
 - issued: not sure what that is, maybe use created to store the start of the project? 
 - slug: what is it for? overlaps with alias?

## NameUsage
 - status is not an enum
   - use a custom status enum: accepted, synonym, misapplied, unassessed 
 - temporalRangeStart/end: typed
 - publishedInYear: int
 - enums
   - nomStatus
   - gender
   - nameType
   - environment
   - notho
