package org.catalogueoflife.editor.coldp;

// One entry from the CLB identifier-scope vocab (GET {clb-base-url}/vocab/identifier-scope), after
// IdScopeService.filter has dropped the generic scopes. `link` is the scope's resolver base URL
// (e.g. "ipni" -> "https://www.ipni.org") -- the frontend's CurieId component uses it to render a
// clickable CURIE. `title` and `link` are optional (may be absent/blank in the source vocab).
public record IdScope(String scope, String title, String link) {}
