package com.devmap.graph;

/** FR-22: an edge the PMO added by hand; stored apart from the index so re-indexing never overwrites it. */
public record ManualEdge(String id, String from, String to, String type, String confidence, String note, String created) {
}
