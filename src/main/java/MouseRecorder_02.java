import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MouseRecorder_02 implements NativeMouseInputListener {
    private static volatile boolean pollingActive = true;
    private static volatile long lastRepaint = 0;
    public enum ActionType { MOVE, PRESS, RELEASE }
    private static int prevX = -1, prevY = -1;

    private static class MouseAction {
        long timeOffset; int x, y; ActionType type; int buttonMask;
        private MouseAction() {}
        MouseAction(long t, int x, int y, ActionType type, int mask) {
            this.timeOffset = t; this.x = x; this.y = y; this.type = type; this.buttonMask = mask;
        }
    }

    // Потокобезопасное хранилище макроса
    private static final List<MouseAction> actions = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean isRecording = false;
    private static volatile boolean isPlaying = false;
    private static volatile boolean isStoppingPlayback = false;
    private static long startTime = 0;

    // 📊 Состояние мыши для UI
    private static volatile int mouseX = 0, mouseY = 0;
    private static volatile boolean[] btnState = new boolean[4]; // 1=LMB, 2=MMB, 3=RMB
    private static JPanel mainPanel;
    private static JFrame mainFrame;

    private static final String TITLE = "Global Mouse Recorder | [R] запись, [P] play, [Esc] стоп, [S/L] json, [Q] выход";

    public static void main(String[] args) {
        Logger.getLogger(GlobalScreen.class.getName()).setLevel(Level.OFF);
        mainFrame = createUI();
        mainFrame.setVisible(true);

        // Запускаем фоновый опрос координат (обходит блокировку хуков 3D-приложениями)
        startCoordinatePolling();
        pollingActive = true;
        startGlobalMousePolling(); // Запускаем опрос координат + кнопок

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pollingActive = false; // Останавливаем поток опроса
            try { GlobalScreen.unregisterNativeHook(); } catch (NativeHookException ignored) {}
        }));

        try {
            GlobalScreen.registerNativeHook();
            //GlobalScreen.addNativeMouseListener(new MouseRecorder());
            //GlobalScreen.addNativeMouseMotionListener(new MouseRecorder());
            setupGlobalHotkeys();
            System.out.println("🌍 Глобальный захват активирован.");
        } catch (NativeHookException ex) {
            System.err.println("❌ Ошибка хука: " + ex.getMessage());
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { GlobalScreen.unregisterNativeHook(); } catch (NativeHookException ignored) {}
        }));
    }

    private static JFrame createUI() {
        JFrame frame = new JFrame(TITLE);
        frame.setSize(360, 300);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setFocusableWindowState(false); // Клики проходят сквозь окно

        mainPanel = new JPanel() {
            { setFocusable(false);
                setBackground(new Color(30, 30, 35, 200));
                setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80), 2));
                setOpaque(true);
            }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Consolas", Font.BOLD, 13));

                // Инструкция
                g2.drawString("🌍 Окно всегда поверх | Клики проходят сквозь", 20, 30);
                g2.drawString("📝 [R] Запись | ▶️ [P] Play | ⏹ [Esc] Стоп", 20, 55);
                g2.drawString("💾 [S] Сохранить | 📂 [L] Загрузить | [Q] Выход", 20, 80);

                // 📊 Статус записи
                String status = isPlaying ? "▶️ Воспроизведение..." :
                        isRecording ? "🔴 ЗАПИСЬ АКТИВНА" :
                                "⏸ Ожидание (" + actions.size() + " событий)";
                g2.drawString("Статус: " + status, 20, 115);

                // 🖱️ Координаты и кнопки (обновляются в реальном времени)
                g2.setFont(new Font("Consolas", Font.BOLD, 15));
                g2.drawString(String.format("📍 X: %4d  Y: %4d", mouseX, mouseY), 20, 160);

                String btnText = String.format("🖱 LMB: %s  MMB: %s  RMB: %s",
                        drawBtn(btnState[1]), drawBtn(btnState[2]), drawBtn(btnState[3]));
                g2.drawString(btnText, 20, 190);

                // Легенда кнопок
                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                g2.setColor(Color.GRAY);
                g2.drawString("⬤ = нажата  ○ = отпущена", 20, 220);
            }

            private String drawBtn(boolean pressed) {
                return pressed ? "⬤" : "○";
            }
        };
        frame.add(mainPanel);
        return frame;
    }

    private static void setupGlobalHotkeys() {
        try {
            GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
                @Override public void nativeKeyPressed(NativeKeyEvent e) {
                    int code = e.getKeyCode();
                    if (code == NativeKeyEvent.VC_R) toggleRecording();
                    else if (code == NativeKeyEvent.VC_P && !isRecording && !actions.isEmpty() && !isPlaying)
                        new Thread(MouseRecorder_02::playback).start();
                    else if (code == NativeKeyEvent.VC_ESCAPE) stopPlayback();
                    else if (code == NativeKeyEvent.VC_S) saveToJson(mainFrame);
                    else if (code == NativeKeyEvent.VC_L) loadFromJson(mainFrame);
                    else if (code == NativeKeyEvent.VC_Q) System.exit(0);
                }
                @Override public void nativeKeyReleased(NativeKeyEvent e) {}
                @Override public void nativeKeyTyped(NativeKeyEvent e) {}
            });
        }
        //catch (NativeHookException ex) {
        catch (Exception ex) {
            System.err.println("❌ Ошибка клавиатурного хука: " + ex.getMessage());
        }
    }

    // 🔄 UI-обновление из любого потока (безопасно для Swing)
    private void updateStatusUI() {
        if (mainPanel != null) SwingUtilities.invokeLater(mainPanel::repaint);
    }

    // 🌍 Обработчики мыши
    @Override public void nativeMouseMoved(NativeMouseEvent e) { recordGlobalAction(e, ActionType.MOVE, 0); }
    @Override public void nativeMouseDragged(NativeMouseEvent e) { recordGlobalAction(e, ActionType.MOVE, 0); }
    @Override public void nativeMousePressed(NativeMouseEvent e) {
        if(e.getButton() >= 1 && e.getButton() <= 3) btnState[e.getButton()] = true;
        updateStatusUI(); recordGlobalAction(e, ActionType.PRESS, e.getButton());
    }
    @Override public void nativeMouseReleased(NativeMouseEvent e) {
        if(e.getButton() >= 1 && e.getButton() <= 3) btnState[e.getButton()] = false;
        updateStatusUI(); recordGlobalAction(e, ActionType.RELEASE, e.getButton());
    }
//    @Override public void nativeMouseMoved(NativeMouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); updateStatusUI(); recordGlobalAction(e, ActionType.MOVE, 0); }
//    @Override public void nativeMouseDragged(NativeMouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); updateStatusUI(); recordGlobalAction(e, ActionType.MOVE, 0); }
//    @Override public void nativeMousePressed(NativeMouseEvent e) {
//        if(e.getButton() >= 1 && e.getButton() <= 3) btnState[e.getButton()] = true;
//        updateStatusUI(); recordGlobalAction(e, ActionType.PRESS, e.getButton());
//    }
//    @Override public void nativeMouseReleased(NativeMouseEvent e) {
//        if(e.getButton() >= 1 && e.getButton() <= 3) btnState[e.getButton()] = false;
//        updateStatusUI(); recordGlobalAction(e, ActionType.RELEASE, e.getButton());
//    }
//    @Override public void nativeMouseClicked(NativeMouseEvent e) {}
    //@Override public void nativeMouseWheelMoved(NativeMouseEvent e) {}

    private void recordGlobalAction(NativeMouseEvent e, ActionType type, int nativeBtn) {
        if (!isRecording) return;
        long offset = System.currentTimeMillis() - startTime;
        int x = e.getX(), y = e.getY();
        if (type == ActionType.MOVE && !actions.isEmpty()) {
            MouseAction last = actions.get(actions.size() - 1);
            if (Math.abs(x - last.x) < 3 && Math.abs(y - last.y) < 3) return;
        }
        int mask = 0;
        if (type != ActionType.MOVE) {
            mask = switch (nativeBtn) {
                case 1 -> InputEvent.BUTTON1_DOWN_MASK;
                case 2 -> InputEvent.BUTTON2_DOWN_MASK;
                case 3 -> InputEvent.BUTTON3_DOWN_MASK;
                default -> 0;
            };
        }
        actions.add(new MouseAction(offset, x, y, type, mask));
    }

    private static void toggleRecording() {
        isRecording = !isRecording;
        if (isRecording) { actions.clear(); startTime = System.currentTimeMillis(); System.out.println("🟢 Запись начата"); }
        else System.out.println("⏹ Запись остановлена. Сохранено: " + actions.size());
        SwingUtilities.invokeLater(mainPanel::repaint);
    }

    private static void stopPlayback() { if (isPlaying) { isStoppingPlayback = true; System.out.println("⏹ Стоп."); } }

    // 🖱️ Умное движение (обходит частичный захват курсора)
    private static void moveCursorSmart(int targetX, int targetY) {
        try {
            Robot robot = new Robot();
            Point cur = MouseInfo.getPointerInfo().getLocation();
            int dx = targetX - cur.x, dy = targetY - cur.y;
            if (Math.abs(dx) < 10 && Math.abs(dy) < 10) { robot.mouseMove(targetX, targetY); return; }
            int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 5;
            if (steps < 1) steps = 1;
            int sx = dx / steps, sy = dy / steps;
            for (int i = 0; i < steps; i++) {
                if (isStoppingPlayback) return;
                robot.mouseMove(cur.x + sx * (i + 1), cur.y + sy * (i + 1));
                Thread.sleep(5);
            }
        } catch (Exception ignored) {}
    }

    private static void playback() {
        isPlaying = true; isStoppingPlayback = false; System.out.println("▶️ Play...");
        try {
            long lastTime = 0;
            List<MouseAction> snap = new ArrayList<>(actions);
            for (MouseAction a : snap) {
                if (isStoppingPlayback) break;
                long delay = a.timeOffset - lastTime;
                if (delay > 0) { long rem = delay; while (rem > 0 && !isStoppingPlayback) { Thread.sleep(Math.min(rem, 50)); rem -= 50; } }
                if (isStoppingPlayback) break;
                lastTime = a.timeOffset;
                moveCursorSmart(a.x, a.y);
                if (a.type == ActionType.PRESS) new Robot().mousePress(a.buttonMask);
                else if (a.type == ActionType.RELEASE) new Robot().mouseRelease(a.buttonMask);
            }
            System.out.println(isStoppingPlayback ? "⏹ Прервано." : "✅ Завершено.");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (AWTException e) { e.printStackTrace(); }
        finally { isPlaying = false; isStoppingPlayback = false; SwingUtilities.invokeLater(mainPanel::repaint); }
    }

    private static void saveToJson(JFrame parent) {
        if (actions.isEmpty()) { System.out.println("⚠️ Пусто."); return; }
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
        c.setSelectedFile(new File("macro.json"));
        if (c.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Writer w = new FileWriter(c.getSelectedFile())) { new GsonBuilder().setPrettyPrinting().create().toJson(actions, w); }
            catch (IOException ex) { System.err.println("❌ " + ex.getMessage()); }
        }
    }

    private static void loadFromJson(JFrame parent) {
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
        if (c.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Reader r = new FileReader(c.getSelectedFile())) {
                List<MouseAction> loaded = new Gson().fromJson(r, new TypeToken<List<MouseAction>>(){}.getType());
                if (loaded != null && !loaded.isEmpty()) {
                    actions.clear(); actions.addAll(loaded); isRecording = false;
                    System.out.println("📂 Загружено " + actions.size());
                    SwingUtilities.invokeLater(mainPanel::repaint);
                }
            } catch (Exception ex) { System.err.println("❌ Ошибка: " + ex.getMessage()); }
        }
    }
    // Метод фонового опроса (добавьте в конец класса)
    private static void startCoordinatePolling() {
        new Thread(() -> {
            while (pollingActive) {
                try {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    if (p != null && (mouseX != p.x || mouseY != p.y)) {
                        mouseX = p.x;
                        mouseY = p.y;
                        // Троттлинг обновления UI (~30 FPS) чтобы не грузить EDT
                        long now = System.currentTimeMillis();
                        if (now - lastRepaint > 33) {
                            lastRepaint = now;
                            SwingUtilities.invokeLater(mainPanel::repaint);
                        }
                    }
                    Thread.sleep(10); // Опрос ~100 Гц
                } catch (Exception ignored) {}
            }
        }, "MousePoller").start();
    }
    // 🌍 Глобальный опрос мыши (обходит блокировку хуков в 3D-приложениях)
    private static void startGlobalMousePolling() {
        new Thread(() -> {
            boolean prevL = false, prevM = false, prevR = false;
            long lastUiUpdate = 0;

            while (pollingActive) {
                try {
                    // 1. Получаем координаты (работает всегда)
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    if (p != null) { mouseX = p.x; mouseY = p.y; }

                    // 2. Опрос кнопок через JNA (Windows)
                    boolean l = getButtonState(1);
                    boolean m = getButtonState(2);
                    boolean r = getButtonState(3);

                    // 3. Запись перемещений
                    if (isRecording && (mouseX != prevX || mouseY != prevY)) {
                        actions.add(new MouseAction(System.currentTimeMillis() - startTime, mouseX, mouseY, ActionType.MOVE, 0));
                        prevX = mouseX; prevY = mouseY;
                    }

                    // 4. Запись кликов (только при смене состояния)
                    long now = System.currentTimeMillis();
                    if (l != prevL) { btnState[1] = l; prevL = l; recordClick(l ? ActionType.PRESS : ActionType.RELEASE, 1, now); }
                    if (m != prevM) { btnState[2] = m; prevM = m; recordClick(m ? ActionType.PRESS : ActionType.RELEASE, 2, now); }
                    if (r != prevR) { btnState[3] = r; prevR = r; recordClick(r ? ActionType.PRESS : ActionType.RELEASE, 3, now); }

                    // 5. Обновление UI (~30 FPS)
                    if (now - lastUiUpdate > 33) {
                        lastUiUpdate = now;
                        SwingUtilities.invokeLater(mainPanel::repaint);
                    }
                    Thread.sleep(8); // ~125 Гц опрос
                } catch (Exception ignored) {}
            }
        }, "MousePoller").start();
    }

    // Чтение состояния кнопки через ядро ОС (Windows)
    private static boolean getButtonState(int btn) {
        try {
            int vk = switch (btn) { case 1 -> 0x01; case 2 -> 0x04; case 3 -> 0x02; default -> 0; };
            return (com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
        } catch (Throwable t) { return false; }
    }

    private static void recordClick(ActionType type, int btn, long eventTime) {
        if (!isRecording) return;
        int mask = switch (btn) {
            case 1 -> InputEvent.BUTTON1_DOWN_MASK;
            case 2 -> InputEvent.BUTTON2_DOWN_MASK;
            case 3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
        actions.add(new MouseAction(eventTime - startTime, mouseX, mouseY, type, mask));
    }

}
