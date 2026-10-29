package com.devmap.config;

/** Entry of ~/.devmap/workspaces.yml: a project and the absolute path of its data folder. */
public record Workspace(String name, String dataDir) {
}
