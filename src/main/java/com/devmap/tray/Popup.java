package com.devmap.tray;

import com.devmap.secret.SecretMasker;
import java.util.List;

/** What the PMO reads in the toast (FR-63) and where a click on it goes. */
public record Popup(String title, String text, String path) {

  private static final int MAX_TEXT = 120;

  /**
   * One item = its own line; several = "Có 3 việc chờ bạn" (counted per ticket), opening their project if they share one, else home.
   * Title and text go through the secret masker last, whatever they were built from (AC-23).
   */
  public static Popup of(List<Notice> batch, SecretMasker masker) {
    long items = batch.stream().map(n -> n.project() + "/" + (n.ticket() == null ? n.kind() : n.ticket())).distinct().count();
    Notice first = batch.get(0);
    if (items == 1) {
      return new Popup(clean(first.title(), masker), clean(first.text(), masker), first.path());
    }
    boolean oneProject = batch.stream().allMatch(n -> n.project().equals(first.project()));
    return new Popup(clean(oneProject ? "[" + first.project() + "]" : "DevMap", masker), "Có " + items + " việc chờ bạn",
        oneProject ? "/" + first.project() : "/");
  }

  /** Secret masker, one line, short. */
  public static String clean(String text, SecretMasker masker) {
    String line = masker.mask(text).text().replaceAll("\\s+", " ").strip();
    return line.length() <= MAX_TEXT ? line : line.substring(0, MAX_TEXT) + "…";
  }
}
