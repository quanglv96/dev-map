package com.devmap.config;

/** A deployable service inside a repo; path is relative to the repo root ("" = the repo itself). */
public record ServiceInfo(String name, String path, String kind) {
}
