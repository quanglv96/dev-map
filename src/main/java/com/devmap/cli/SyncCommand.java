package com.devmap.cli;

import com.devmap.sync.SyncService;
import com.devmap.sync.SyncService.Status;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "sync", mixinStandardHelpOptions = true, description = "Commit, pull and push the DevMap data repo now (FR-48).")
class SyncCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    Status s = Core.get(SyncService.class).syncNow();
    print(s);
    return s.state() == SyncService.State.ERROR ? 1 : 0;
  }

  static void print(Status s) {
    System.out.println("Đồng bộ: " + s.state() + " (máy " + s.machine() + ", " + s.dataDir() + " ↔ " + s.remote() + ")");
    if (s.message() != null) {
      System.out.println("  " + s.message());
    }
    if (s.ahead() > 0) {
      System.out.println("  " + s.ahead() + " commit chưa đẩy" + (s.confirmPush() ? " (sync.auto_push=false: đẩy ở giao diện)" : ""));
    }
    s.blocked().forEach(b -> System.out.println("  Chặn commit, giống bí mật: " + b));
    s.conflicts().forEach(c -> System.out.println("  Xung đột: " + c.file() + " ↔ " + c.conflictFile()));
    if (s.warning() != null) {
      System.out.println("  Cảnh báo: " + s.warning());
    }
  }
}
