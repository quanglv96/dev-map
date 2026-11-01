package com.devmap.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * FR-59: base icon plus one mark, drawn at the size the tray really uses. Only BufferedImage, so it also runs without a screen. When
 * several apply the count wins (it is what the PMO must act on), then the error mark, then the running dot.
 */
public final class TrayIconRenderer {

  private static final int[] SIZES = {16, 24, 32};
  private static final Color RED = new Color(0xDC, 0x26, 0x26);
  private static final Color YELLOW = new Color(0xF5, 0x9E, 0x0B);

  private TrayIconRenderer() {
  }

  /** The prepared size closest to and not below what the tray asks for in device pixels. */
  public static int pickSize(double wanted) {
    for (int size : SIZES) {
      if (size >= wanted) {
        return size;
      }
    }
    return SIZES[SIZES.length - 1];
  }

  public static BufferedImage render(int size, int pending, int running, boolean error) {
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      drawBase(g, size);
      if (pending > 0) {
        drawCount(g, size, pending);
      } else if (error) {
        drawError(g, size);
      } else if (running > 0) {
        int d = Math.round(size * 0.5f);
        dot(g, size - d, size - d, d, YELLOW);
      }
    } finally {
      g.dispose();
    }
    return image;
  }

  private static void drawBase(Graphics2D g, int size) {
    try (InputStream in = TrayIconRenderer.class.getResourceAsStream("/tray/icon-" + size + ".png")) {
      if (in != null) {
        g.drawImage(ImageIO.read(in), 0, 0, size, size, null);
        return;
      }
    } catch (IOException e) {
      // the plain square below still shows the state
    }
    g.setColor(new Color(0x25, 0x63, 0xEB));
    g.fillRoundRect(0, 0, size, size, size / 3, size / 3);
  }

  private static void drawCount(Graphics2D g, int size, int pending) {
    String text = pending > 9 ? "9+" : String.valueOf(pending);
    int d = Math.round(size * 0.72f);
    dot(g, size - d, 0, d, RED);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(d * (text.length() > 1 ? 0.62f : 0.85f))));
    FontMetrics m = g.getFontMetrics();
    g.setColor(Color.WHITE);
    g.drawString(text, size - d + (d - m.stringWidth(text)) / 2, (d - m.getHeight()) / 2 + m.getAscent());
  }

  /** A red dot with a white cross. */
  private static void drawError(Graphics2D g, int size) {
    int d = Math.round(size * 0.62f);
    int x = size - d;
    int y = size - d;
    dot(g, x, y, d, RED);
    int gap = Math.max(2, d / 4);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(Math.max(1.5f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.drawLine(x + gap, y + gap, x + d - gap - 1, y + d - gap - 1);
    g.drawLine(x + d - gap - 1, y + gap, x + gap, y + d - gap - 1);
  }

  private static void dot(Graphics2D g, int x, int y, int d, Color color) {
    g.setColor(color);
    g.fillOval(x, y, d, d);
  }
}
