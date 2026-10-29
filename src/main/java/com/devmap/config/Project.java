package com.devmap.config;

/** A registered project; config is null and error is set when its data folder cannot be read. */
public record Project(String name, String dataDir, ProjectConfig config, String error) {
}
