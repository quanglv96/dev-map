package com.devmap.tray;

import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BooleanSupplier;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** java.awt.SystemTray; on Windows 10/11 displayMessage appears as a toast. All AWT work runs on the event dispatch thread. */
@Component
public class AwtTrayNotifier implements TrayNotifier {

  /** The icon's double-click arrives with mouse events, a click on the toast without any; that tells the two apart. */
  private static final long MOUSE_MILLIS = 1_000;
  private static final Logger LOG = LoggerFactory.getLogger(AwtTrayNotifier.class);

  private final BooleanSupplier supported;
  private TrayActions actions;
  private TrayIcon icon;
  private TrayView shown;
  private String popupPath;
  private long lastMouse;

  @Autowired
  public AwtTrayNotifier() {
    this(AwtTrayNotifier::trayAvailable);
  }

  AwtTrayNotifier(BooleanSupplier supported) {
    this.supported = supported;
  }

  private static boolean trayAvailable() {
    try {
      return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
    } catch (AWTError | UnsupportedOperationException | SecurityException | LinkageError e) {
      LOG.info("Không dùng được khay hệ thống: {}", e.toString());
      return false;
    }
  }

  @Override
  public boolean start(TrayActions trayActions) {
    if (!supported.getAsBoolean()) {
      LOG.info("Máy này không có khay hệ thống: DevMap chạy không có biểu tượng.");
      return false;
    }
    actions = trayActions;
    boolean[] added = {false};
    try {
      EventQueue.invokeAndWait(() -> added[0] = add());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (InvocationTargetException | AWTError e) {
      LOG.warn("Không tạo được biểu tượng khay: {}", e.toString());
    }
    return added[0];
  }

  private boolean add() {
    try {
      icon = new TrayIcon(TrayIconRenderer.render(iconSize(), 0, 0, false), "DevMap");
      icon.setImageAutoSize(true);
      icon.addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          lastMouse = System.currentTimeMillis();
        }
      });
      icon.addActionListener(e -> onAction());
      SystemTray.getSystemTray().add(icon);
      return true;
    } catch (AWTException | UnsupportedOperationException | SecurityException e) {
      LOG.warn("Không tạo được biểu tượng khay: {}", e.toString());
      icon = null;
      return false;
    }
  }

  private void onAction() {
    boolean fromIcon = System.currentTimeMillis() - lastMouse < MOUSE_MILLIS;
    String path = fromIcon || popupPath == null ? "/" : popupPath;
    if (!fromIcon) {
      popupPath = null;
    }
    actions.open(path);
  }

  /** Device pixels the tray uses (24 at 150 %): drawn crisp at that size, setImageAutoSize only fits what is left. */
  private static int iconSize() {
    Dimension logical = SystemTray.getSystemTray().getTrayIconSize();
    double scale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
        .getDefaultTransform().getScaleX();
    return TrayIconRenderer.pickSize(logical.getWidth() * scale);
  }

  @Override
  public void show(TrayView view) {
    EventQueue.invokeLater(() -> {
      if (icon == null || view.equals(shown)) {
        return;
      }
      icon.setImage(TrayIconRenderer.render(iconSize(), view.pending(), view.running(), view.error()));
      icon.setToolTip(view.tooltip());
      if (shown == null || !view.menuPart().equals(shown.menuPart())) {
        icon.setPopupMenu(menu(view)); // rebuilt only when its content changed: an open menu stays open while agents run
      }
      shown = view;
    });
  }

  private PopupMenu menu(TrayView view) {
    PopupMenu menu = new PopupMenu();
    menu.add(item("Mở DevMap", () -> actions.open("/")));
    Menu waiting = new Menu("Việc chờ bạn (" + view.pending() + ")");
    waiting.setEnabled(!view.rows().isEmpty());
    view.rows().forEach(w -> waiting.add(item(w.label(), () -> actions.open(w.path()))));
    menu.add(waiting);
    Menu projects = new Menu("Dự án");
    projects.setEnabled(!view.projects().isEmpty());
    view.projects().forEach(p -> projects.add(item(p, () -> actions.open("/" + p))));
    menu.add(projects);
    Menu snooze = new Menu(view.snoozed() ? "Tạm tắt thông báo (đang tắt)" : "Tạm tắt thông báo");
    snooze.add(item("1 giờ", () -> actions.snoozeOneHour()));
    snooze.add(item("Đến 7 giờ sáng", () -> actions.snoozeUntilMorning()));
    MenuItem resume = item("Bật lại", () -> actions.resumeNotifications());
    resume.setEnabled(view.snoozed());
    snooze.add(resume);
    menu.add(snooze);
    if (view.canSync()) {
      menu.add(item("Đồng bộ ngay", () -> actions.syncNow()));
    }
    CheckboxMenuItem autostart = new CheckboxMenuItem("Khởi động cùng Windows", view.autostart());
    autostart.setEnabled(view.canAutostart());
    autostart.addItemListener(e -> actions.setAutostart(e.getStateChange() == ItemEvent.SELECTED));
    menu.add(autostart);
    menu.addSeparator();
    menu.add(item("Thoát DevMap", this::confirmExit));
    return menu;
  }

  private static MenuItem item(String label, Runnable action) {
    MenuItem item = new MenuItem(label);
    item.addActionListener(e -> action.run());
    return item;
  }

  /** FR-67: on top of everything, because the tray has no window of its own to sit in front of. */
  private void confirmExit() {
    int running = actions.runningAgents();
    if (running > 0) {
      JOptionPane pane = new JOptionPane("Còn " + running + " phiên agent đang chạy hoặc chờ lượt. Thoát bây giờ sẽ dừng chúng giữa chừng."
          + "\nVẫn thoát DevMap?", JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
      JDialog dialog = pane.createDialog("DevMap");
      dialog.setAlwaysOnTop(true);
      dialog.setVisible(true);
      Object answer = pane.getValue();
      dialog.dispose();
      if (!Integer.valueOf(JOptionPane.YES_OPTION).equals(answer)) {
        return;
      }
    }
    actions.exit();
  }

  @Override
  public void notify(Popup popup) {
    EventQueue.invokeLater(() -> {
      if (icon != null) {
        popupPath = popup.path();
        icon.displayMessage(popup.title(), popup.text(), TrayIcon.MessageType.INFO);
      }
    });
  }

  @Override
  public void stop() {
    Runnable remove = () -> {
      if (icon != null) {
        SystemTray.getSystemTray().remove(icon);
        icon = null;
      }
    };
    try {
      if (EventQueue.isDispatchThread()) {
        remove.run();
      } else {
        EventQueue.invokeAndWait(remove);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (InvocationTargetException | AWTError e) {
      LOG.debug("Gỡ biểu tượng khay: {}", e.toString());
    }
  }
}
