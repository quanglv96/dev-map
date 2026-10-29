package com.devmap.ticket;

/** Published after any change to a ticket; the web layer forwards it to open tabs over SSE. */
public record TaskChanged(String project, String taskId) {
}
