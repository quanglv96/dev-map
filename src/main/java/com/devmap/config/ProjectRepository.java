package com.devmap.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/** A project's data folder (SRS "Lưu trữ dữ liệu"): config.yml, project.md, conventions.md, rules/, graph/, tasks/. */
@Repository
public class ProjectRepository {

  private static final String CONFIG = "config.yml";
  private static final String BEGIN = "<!-- devmap:repos -->";
  private static final String END = "<!-- /devmap:repos -->";
  private static final Pattern REPOS_SECTION = Pattern.compile(Pattern.quote(BEGIN) + ".*?" + Pattern.quote(END), Pattern.DOTALL);

  public boolean exists(Path dir) {
    return Files.isRegularFile(dir.resolve(CONFIG));
  }

  public ProjectConfig load(Path dir) {
    return Yaml.read(dir.resolve(CONFIG), ProjectConfig.class);
  }

  /** Creates any missing folders/files, then writes config.yml and refreshes the repo list in project.md. */
  public void save(Path dir, ProjectConfig config) {
    try {
      for (String sub : new String[] {"rules", "graph", "tasks"}) {
        Files.createDirectories(dir.resolve(sub));
      }
      Path conventions = dir.resolve("conventions.md");
      if (!Files.exists(conventions)) {
        Files.writeString(conventions, "# Coding convention – " + config.project() + "\n\nChưa thu thập.\n");
      }
      // FR-51: config.yml is synced between machines, so it never holds this machine's repo paths
      Yaml.write(dir.resolve(CONFIG), new ProjectConfig(config.project(), config.color(), config.ticketCode(), config.ticketPattern(),
          config.branchPatterns(), config.repos().stream().map(r -> r.withPath(null)).toList(), config.environments(),
          config.scanFullProject(), config.secrets()));
      writeProjectMd(dir.resolve("project.md"), config);
    } catch (IOException e) {
      throw new UncheckedIOException("Không ghi được folder dữ liệu " + dir + ": " + e.getMessage(), e);
    }
  }

  /** FR-52: keeps the config.yml from before the migration as config.yml.bak (first migration only). */
  public void backup(Path dir) {
    Path bak = dir.resolve(CONFIG + ".bak");
    try {
      if (!Files.exists(bak)) {
        Files.copy(dir.resolve(CONFIG), bak);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Không sao lưu được " + bak + ": " + e.getMessage(), e);
    }
  }

  /** PMO owns the free text of project.md; DevMap only rewrites the generated repo/service section. */
  private void writeProjectMd(Path file, ProjectConfig config) throws IOException {
    String section = reposSection(config);
    String text = Files.exists(file)
        ? Files.readString(file)
        : "# " + config.project() + "\n\n## Mô tả hệ thống\n\n_(PMO bổ sung)_\n\n## Repo / service\n\n" + BEGIN + "\n" + END + "\n";
    Matcher m = REPOS_SECTION.matcher(text);
    text = m.find() ? m.replaceFirst(Matcher.quoteReplacement(section)) : text + "\n" + section + "\n";
    Yaml.writeText(file, text);
  }

  private static String reposSection(ProjectConfig config) {
    StringBuilder sb = new StringBuilder(BEGIN).append('\n');
    for (RepoConfig repo : config.repos()) {
      sb.append("- **").append(repo.name()).append("** (nhánh nền `").append(repo.baseBranch()).append("`)\n");
      for (ServiceInfo s : repo.services()) {
        sb.append("  - ").append(s.name()).append(" – ").append(s.kind())
            .append(s.path().isEmpty() ? "" : " – `" + s.path() + "`").append('\n');
      }
    }
    return sb.append(END).toString();
  }
}
