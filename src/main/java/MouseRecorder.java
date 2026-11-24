import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser.HHOOK;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MouseRecorder {

    public enum ActionType { MOVE, PRESS, RELEASE, WHEEL }

    public static class MouseAction {
        long timeOffset; int x, y; ActionType type; int buttonMask; int wheelDelta;
        private MouseAction() {}
        public MouseAction(long t, int x, int y, ActionType type, int mask, int wheel) {
            this.timeOffset = t; this.x = x; this.y = y; this.type = type; this.buttonMask = mask; this.wheelDelta = wheel;
        }
    }

    // Константы Windows
    private static final int WH_MOUSE_LL = 14;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_RBUTTONDOWN = 0x0204;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_MBUTTONDOWN = 0x0207;
    private static final int WM_MBUTTONUP = 0x0208;
    private static final int WM_MOUSEWHEEL = 0x020A;
    private static final int WM_QUIT = 0x0012;

    @Structure.FieldOrder({"pt", "mouseData", "flags", "time", "dwExtraInfo"})
    public static class MSLLHOOKSTRUCT extends Structure {
        public WinDef.POINT pt = new WinDef.POINT();
        public int mouseData;
        public int flags;
        public int time;
        public int dwExtraInfo;
        public MSLLHOOKSTRUCT(Pointer p) { super(p); read(); }
    }

    // Состояние приложения
    private static final List<MouseAction> actions = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean isRecording = false;
    private static volatile boolean isPlaying = false;
    private static volatile boolean isStoppingPlayback = false;
    private static volatile boolean isPaused = false;
    private static volatile boolean hotkeysEnabled = true;
    private static volatile double playbackSpeed = 1.0;
    private static long startTime = 0;

    private static volatile int mouseX = 0, mouseY = 0;
    private static volatile boolean[] btnState = new boolean[4];
    private static volatile long lastUiUpdate = 0;

    // 📸 Настройки скриншотов и превью
    private static final int PREVIEW_BASE_SIZE = 50;
    private static final String SCREENSHOT_DIR = "screenshots";
    private static volatile double previewScale = 1.0;
    private static volatile boolean captureOnClickOnly = false;
    private static volatile boolean autoSaveScreenshots = false;
    private static volatile int screenshotCounter = 0;
    private static JLabel cursorPreviewLabel = null;
    private static Robot previewRobot = null;
    private static volatile long lastPreviewTime = 0;

    private static JFrame mainFrame;
    private static JPanel mainPanel;
    private static final String TITLE = "Global Mouse Recorder | [R] запись, [P] play, [Esc] стоп, [S/L] json, [Q] выход";

    private static HHOOK mouseHook = null;
    private static WinUser.LowLevelMouseProc mouseProc = null;
    private static Thread hookThread = null;

    public static void main(String[] args) {
        Logger.getLogger(GlobalScreen.class.getName()).setLevel(Level.INFO);
        mainFrame = createUI();
        mainFrame.setVisible(true);

        setupGlobalHotkeys();
        installLowLevelMouseHook();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            uninstallMouseHook();
            try { GlobalScreen.unregisterNativeHook(); } catch (NativeHookException ignored) {}
        }));
    }

    private static void installLowLevelMouseHook() {
        Native.setProtected(true);
        mouseProc = new WinUser.LowLevelMouseProc() {
            @Override
            public LRESULT callback(int nCode, WPARAM wParam, WinUser.MSLLHOOKSTRUCT lParam) {
                if (nCode < 0) {
                    return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam, new LPARAM(lParam.pt.getPointer().getLong(0)));
                }

                //Pointer ptr = new Pointer(lParam.longValue());
                Pointer ptr = lParam.getPointer();
                MSLLHOOKSTRUCT mhs = new MSLLHOOKSTRUCT(ptr);
                int x = mhs.pt.x, y = mhs.pt.y, msg = wParam.intValue();

                if (mouseX != x || mouseY != y) {
                    mouseX = x; mouseY = y;
                    if (isRecording && !isPaused) {
                        actions.add(new MouseAction(System.currentTimeMillis() - startTime, x, y, ActionType.MOVE, 0, 0));
                    }
                    updateUI();
                }

                ActionType type = null; int btn = 0, mask = 0, wheel = 0;
                boolean isClick = false;
                switch (msg) {
                    case WM_LBUTTONDOWN: btn=1; mask=InputEvent.BUTTON1_DOWN_MASK; type=ActionType.PRESS; isClick=true; break;
                    case WM_LBUTTONUP:   btn=1; mask=InputEvent.BUTTON1_DOWN_MASK; type=ActionType.RELEASE; break;
                    case WM_MBUTTONDOWN: btn=2; mask=InputEvent.BUTTON2_DOWN_MASK; type=ActionType.PRESS; isClick=true; break;
                    case WM_MBUTTONUP:   btn=2; mask=InputEvent.BUTTON2_DOWN_MASK; type=ActionType.RELEASE; break;
                    case WM_RBUTTONDOWN: btn=3; mask=InputEvent.BUTTON3_DOWN_MASK; type=ActionType.PRESS; isClick=true; break;
                    case WM_RBUTTONUP:   btn=3; mask=InputEvent.BUTTON3_DOWN_MASK; type=ActionType.RELEASE; break;
                    case WM_MOUSEWHEEL:  wheel = (short)((mhs.mouseData >> 16) & 0xFFFF); type = ActionType.WHEEL; break;
                }

                if (type != null && isRecording && !isPaused) {
                    if (btn >= 1 && btn <= 3) btnState[btn] = (type == ActionType.PRESS);
                    actions.add(new MouseAction(System.currentTimeMillis() - startTime, mouseX, mouseY, type, mask, wheel));
                    updateUI();
                }

                // 📸 Логика захвата скриншота
                if (captureOnClickOnly) {
                    if (isClick) captureCursorPreview();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastPreviewTime > 100) {
                        lastPreviewTime = now;
                        captureCursorPreview();
                    }
                }

                //return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam, lParam);
                return User32.INSTANCE.CallNextHookEx(mouseHook, nCode, wParam, new LPARAM(lParam.pt.getPointer().getLong(0)));
            }
        };

        hookThread = new Thread(() -> {
            mouseHook = User32.INSTANCE.SetWindowsHookEx(WH_MOUSE_LL, mouseProc, null, 0);
            if (mouseHook == null) {
                System.err.println("⚠️ Hook failed! Run as Administrator. Error: " + Native.getLastError());
                return;
            }
            System.out.println("🌍 WH_MOUSE_LL успешно установлен.");
            WinUser.MSG msg = new WinUser.MSG();
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
            User32.INSTANCE.UnhookWindowsHookEx(mouseHook);
            mouseHook = null;
            System.out.println("🔌 Хук мыши отключён.");
        }, "MouseHookThread");
        hookThread.setDaemon(true);
        hookThread.start();
    }

    private static void uninstallMouseHook() {
        if (hookThread != null && hookThread.isAlive()) {
            User32.INSTANCE.PostThreadMessage((int) hookThread.getId(), WM_QUIT, new WPARAM(0), new LPARAM(0));
            try { hookThread.join(1000); } catch (InterruptedException ignored) {}
        }
    }

    private static void updateUI() {
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate > 33) {
            lastUiUpdate = now;
            SwingUtilities.invokeLater(mainPanel::repaint);
        }
    }

    private static void captureCursorPreview() {
        if (cursorPreviewLabel == null) return;
        try {
            if (previewRobot == null) previewRobot = new Robot();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int size = PREVIEW_BASE_SIZE;
            int x = Math.max(0, mouseX - size / 2);
            int y = Math.max(0, mouseY - size / 2);
            int w = Math.min(size, screen.width - x);
            int h = Math.min(size, screen.height - y);
            if (w <= 0 || h <= 0) return;

            BufferedImage original = previewRobot.createScreenCapture(new Rectangle(x, y, w, h));

            // Масштабирование для отображения
            int scaledW = Math.max(1, (int)(w * previewScale));
            int scaledH = Math.max(1, (int)(h * previewScale));
            BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(original, 0, 0, scaledW, scaledH, null);
            g2d.dispose();

            SwingUtilities.invokeLater(() -> cursorPreviewLabel.setIcon(new ImageIcon(scaled)));

            // Автосохранение
            if (autoSaveScreenshots) {
                File dir = new File(SCREENSHOT_DIR);
                if (!dir.exists()) dir.mkdirs();
                String filename = String.format("click_%04d_%d.png", ++screenshotCounter, System.currentTimeMillis());
                ImageIO.write(original, "png", new File(dir, filename));
            }
        } catch (Exception ignored) {}
    }

    private static void changePreviewScale(double delta) {
        previewScale = Math.max(0.5, Math.min(4.0, previewScale + delta));
        System.out.printf("🔍 Масштаб превью: %.1fx\n", previewScale);
        updateUI();
    }

    private static JFrame createUI() {
        JFrame frame = new JFrame(TITLE);
        frame.setSize(980, 650);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setFocusableWindowState(false);
        frame.setLayout(new BorderLayout());

        mainPanel = new JPanel() {
            { setFocusable(false); setBackground(new Color(30, 30, 35, 210));
              setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80), 2)); }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Consolas", Font.BOLD, 12));

                g2.drawString("🌍 Окно поверх | Клики сквозь | [R] запись | [P] play | [Esc] стоп", 20, 25);
                g2.drawString("💾 [S] Сохранить | 📂 [L] Загрузить | [Q] выход | [F11] хоткеи ON/OFF", 20, 45);
                g2.drawString("⏸ [Space] пауза | ⚡ [F9/F10] скорость | [F11] 1x", 20, 65);
                g2.drawString("🔍 [F1] масштаб + | [F2] масштаб - | [F3] клик-захват | [F4] автосохранение", 20, 85);

                String statusText = isPaused ? "⏸ ПАУЗА" : isPlaying ? "▶️ Play" : isRecording ? "🔴 Запись" : "⏸ Ожидание";
                g2.setColor(hotkeysEnabled ? Color.GREEN : Color.ORANGE);
                g2.drawString(String.format("Статус: %s | Хоткеи: %s | ⚡ %.1fx", statusText, hotkeysEnabled ? "ON" : "OFF", playbackSpeed), 20, 115);

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Consolas", Font.BOLD, 14));
                g2.drawString(String.format("📍 X: %4d  Y: %4d", mouseX, mouseY), 20, 145);
                g2.drawString(String.format("🖱 LMB: %s  MMB: %s  RMB: %s",
                    drawBtn(btnState[1]), drawBtn(btnState[2]), drawBtn(btnState[3])), 20, 170);

                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                g2.setColor(Color.CYAN);
                g2.drawString(String.format("📸 Превью: %.1fx | Клик-захват: %s | Сохранение: %s",
                    previewScale, captureOnClickOnly ? "ON" : "OFF", autoSaveScreenshots ? "ON" : "OFF"), 20, 195);
                g2.setColor(Color.GRAY);
                g2.drawString("⬤ = нажата  ○ = отпущена  📁 Папка: ./screenshots/", 20, 215);
            }
            private String drawBtn(boolean pressed) { return pressed ? "⬤" : "○"; }
        };
        frame.add(mainPanel, BorderLayout.CENTER);

        // 📸 Панель превью
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBackground(new Color(30, 30, 35, 210));
        previewPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY, 1), "Под курсором",
            javax.swing.border.TitledBorder.CENTER, javax.swing.border.TitledBorder.TOP,
            new Font("Consolas", Font.BOLD, 11), Color.WHITE));
        cursorPreviewLabel = new JLabel();
        cursorPreviewLabel.setHorizontalAlignment(JLabel.CENTER);
        cursorPreviewLabel.setPreferredSize(new Dimension(PREVIEW_BASE_SIZE * 2, PREVIEW_BASE_SIZE * 2));
        previewPanel.add(cursorPreviewLabel, BorderLayout.CENTER);
        frame.add(previewPanel, BorderLayout.EAST);

        return frame;
    }

    private static void setupGlobalHotkeys() {
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
                @Override public void nativeKeyPressed(NativeKeyEvent e) {
                    int code = e.getKeyCode();
                    if (code == NativeKeyEvent.VC_F11) {
                        hotkeysEnabled = !hotkeysEnabled;
                        System.out.println("🔑 Хоткеи: " + (hotkeysEnabled ? "ВКЛ" : "ВЫКЛ"));
                        updateUI(); return;
                    }
                    if (!hotkeysEnabled) return;

                    switch (code) {
                        case NativeKeyEvent.VC_R: toggleRecording(); break;
                        case NativeKeyEvent.VC_P:
                            if (!isRecording && !actions.isEmpty() && !isPlaying) new Thread(MouseRecorder::playback).start();
                            break;
                        case NativeKeyEvent.VC_ESCAPE: stopPlayback(); break;
                        case NativeKeyEvent.VC_S: saveToJson(mainFrame); break;
                        case NativeKeyEvent.VC_L: loadFromJson(mainFrame); break;
                        case NativeKeyEvent.VC_Q: System.exit(0); break;
                        case NativeKeyEvent.VC_SPACE:
                            if (isRecording || isPlaying) { isPaused = !isPaused; System.out.println(isPaused ? "⏸ Пауза" : "▶️ Продолжение"); updateUI(); }
                            break;
                        case NativeKeyEvent.VC_F9:  changeSpeed(-0.5); break;
                        case NativeKeyEvent.VC_F10: changeSpeed(0.5);  break;
                        case NativeKeyEvent.VC_F11: playbackSpeed = 1.0; System.out.println("⚡ Скорость: 1.0x"); updateUI(); break;
                        case NativeKeyEvent.VC_F1:  changePreviewScale(0.5); break;
                        case NativeKeyEvent.VC_F2:  changePreviewScale(-0.5); break;
                        case NativeKeyEvent.VC_F3:  toggleCaptureOnClick(); break;
                        case NativeKeyEvent.VC_F4:  toggleAutoSaveScreenshots(); break;
                    }
                }
                @Override public void nativeKeyReleased(NativeKeyEvent e) {}
                @Override public void nativeKeyTyped(NativeKeyEvent e) {}
            });
            System.out.println("⌨️ Глобальные хоткеи активированы.");
        } catch (NativeHookException ex) {
            System.err.println("❌ Ошибка регистрации JNativeHook: " + ex.getMessage());
        }
    }

    private static void changeSpeed(double delta) {
        playbackSpeed = Math.max(0.1, Math.min(10.0, playbackSpeed + delta));
        System.out.printf("⚡ Скорость: %.1fx\n", playbackSpeed);
        updateUI();
    }

    private static void toggleCaptureOnClick() {
        captureOnClickOnly = !captureOnClickOnly;
        System.out.println("📸 Захват при кликах: " + (captureOnClickOnly ? "ON" : "OFF"));
        updateUI();
    }

    private static void toggleAutoSaveScreenshots() {
        autoSaveScreenshots = !autoSaveScreenshots;
        System.out.println("📁 Автосохранение: " + (autoSaveScreenshots ? "ON" : "OFF"));
        if (autoSaveScreenshots) {
            File dir = new File(SCREENSHOT_DIR);
            if (!dir.exists()) dir.mkdirs();
            System.out.println("💾 Папка сохранения: " + dir.getAbsolutePath());
        }
        updateUI();
    }

    private static void toggleRecording() {
        isRecording = !isRecording;
        if (isRecording) { actions.clear(); startTime = System.currentTimeMillis(); isPaused = false; System.out.println("🟢 Запись начата"); }
        else System.out.println("⏹ Запись остановлена. Сохранено: " + actions.size());
        updateUI();
    }

    private static void stopPlayback() {
        if (isPlaying) { isStoppingPlayback = true; isPaused = false; System.out.println("⏹ Воспроизведение остановлено."); }
    }

    private static void moveCursorSmart(int targetX, int targetY) {
        try {
            Robot robot = new Robot();
            Point cur = MouseInfo.getPointerInfo().getLocation();
            int dx = targetX - cur.x, dy = targetY - cur.y;
            if (Math.abs(dx) < 10 && Math.abs(dy) < 10) { robot.mouseMove(targetX, targetY); return; }
            int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) / 5);
            int sx = dx / steps, sy = dy / steps;
            for (int i = 0; i < steps; i++) {
                if (isStoppingPlayback || isPaused) return;
                robot.mouseMove(cur.x + sx * (i + 1), cur.y + sy * (i + 1));
                Thread.sleep(5);
            }
        } catch (Exception ignored) {}
    }

    private static void playback() {
        isPlaying = true; isStoppingPlayback = false; isPaused = false;
        System.out.printf("▶️ Воспроизведение на скорости %.1fx...\n", playbackSpeed);
        try {
            long lastTime = 0;
            List<MouseAction> snap = new ArrayList<>(actions);
            for (MouseAction a : snap) {
                if (isStoppingPlayback) break;
                long delay = (long) ((a.timeOffset - lastTime) / playbackSpeed);
                if (delay > 0) {
                    long remaining = delay;
                    long pauseStart = 0;
                    while (remaining > 0 && !isStoppingPlayback) {
                        if (isPaused) { if (pauseStart == 0) pauseStart = System.currentTimeMillis(); Thread.sleep(50); continue; }
                        if (pauseStart > 0) { remaining -= (System.currentTimeMillis() - pauseStart); pauseStart = 0; }
                        long sleepMs = Math.min(remaining, 50);
                        Thread.sleep(sleepMs);
                        remaining -= sleepMs;
                    }
                }
                if (isStoppingPlayback) break;
                lastTime = a.timeOffset;

                moveCursorSmart(a.x, a.y);
                if (a.type == ActionType.PRESS) new Robot().mousePress(a.buttonMask);
                else if (a.type == ActionType.RELEASE) new Robot().mouseRelease(a.buttonMask);
                else if (a.type == ActionType.WHEEL) new Robot().mouseWheel(-(a.wheelDelta / 120));
            }
            System.out.println(isStoppingPlayback ? "⏹ Прервано." : "✅ Завершено.");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (AWTException e) { e.printStackTrace(); }
        finally { isPlaying = false; isStoppingPlayback = false; isPaused = false; updateUI(); }
    }

    private static void saveToJson(JFrame parent) {
        if (actions.isEmpty()) { System.out.println("⚠️ Нечего сохранять."); return; }
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter("JSON Macro", "json"));
        c.setSelectedFile(new File("mouse_macro.json"));
        if (c.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Writer w = new FileWriter(c.getSelectedFile())) {
                new GsonBuilder().setPrettyPrinting().create().toJson(actions, w);
                System.out.println("💾 Сохранено: " + c.getSelectedFile().getAbsolutePath());
            } catch (IOException ex) { System.err.println("❌ Ошибка: " + ex.getMessage()); }
        }
    }

    private static void loadFromJson(JFrame parent) {
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter("JSON Macro", "json"));
        if (c.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Reader r = new FileReader(c.getSelectedFile())) {
                Type listType = new TypeToken<List<MouseAction>>(){}.getType();
                List<MouseAction> loaded = new Gson().fromJson(r, listType);
                if (loaded != null && !loaded.isEmpty()) {
                    actions.clear(); actions.addAll(loaded); isRecording = false; isPaused = false;
                    System.out.println("📂 Загружено " + loaded.size() + " действий.");
                    updateUI();
                }
            } catch (Exception ex) { System.err.println("❌ Ошибка загрузки: " + ex.getMessage()); }
        }
    }
}
