import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class MouseRecorderOpenCV_awt {
    private static volatile boolean isStoppingPlayback = false;
    // Типы действий мыши
    public enum ActionType { MOVE, PRESS, RELEASE }

    // Класс действия (Gson сериализует поля автоматически)
    private static class MouseAction {
        long timeOffset;
        int x, y;
        ActionType type;
        int buttonMask;

        // No-arg constructor для Gson
        private MouseAction() {}

        MouseAction(long timeOffset, int x, int y, ActionType type, int buttonMask) {
            this.timeOffset = timeOffset;
            this.x = x;
            this.y = y;
            this.type = type;
            this.buttonMask = buttonMask;
        }
    }

    private static final List<MouseAction> actions = new ArrayList<>();
    private static volatile boolean isRecording = false;
    private static volatile boolean isPlaying = false;
    private static long startTime = 0;
    private static final String TITLE = "Mouse Recorder | [R] запись, [P] воспроизведение, [S/L] сохранить/загрузить, [Q] выход";

    public static void main(String[] args) {
        // Настройка окна
        JFrame frame = new JFrame(TITLE);
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        JPanel panel = new JPanel() {
            { setFocusable(true); setBackground(new Color(30, 30, 35)); }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Color.WHITE);
                int start = 40;
                int step = 25;
                int i = 0;
                int x = 50;
                g.setFont(new Font("Consolas", Font.BOLD, 14));
                g.drawString("📝 [R] Начать/остановить запись", x, start + step * i++);
                g.drawString("💾 [S] Сохранить в JSON", x, start + step * i++);
                g.drawString("📂 [L] Загрузить из JSON", x, start + step * i++);
                g.drawString("▶️ [P] Воспроизвести", x, start + step * i++);
                g.drawString("▶️ [Esc] Остановить воспроизведение", x, start + step * i++);
                g.drawString("🔴 Статус: " + getStatusText(), x, start + step * i++);
            }
        };

        // Слушатель мыши
        MouseAdapter mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { recordAction(e, ActionType.PRESS, e.getButton()); }
            @Override
            public void mouseReleased(MouseEvent e) { recordAction(e, ActionType.RELEASE, e.getButton()); }
            @Override
            public void mouseMoved(MouseEvent e) { recordAction(e, ActionType.MOVE, 0); }
            @Override
            public void mouseDragged(MouseEvent e) { recordAction(e, ActionType.MOVE, 0); }
        };

        panel.addMouseListener(mouseListener);
        panel.addMouseMotionListener(mouseListener);
        frame.add(panel);
        frame.setVisible(true);

        // Вставьте сразу после: frame.setVisible(true);
        int condition = JComponent.WHEN_IN_FOCUSED_WINDOW;
        InputMap inputMap = panel.getInputMap(condition);
        ActionMap actionMap = panel.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "toggleRecord");
        actionMap.put("toggleRecord", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { toggleRecording(panel); }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "playback");
        actionMap.put("playback", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (!isRecording && !actions.isEmpty() && !isPlaying) new Thread(MouseRecorderOpenCV_awt::playback).start();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "save");
        actionMap.put("save", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { saveToJson(frame); }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, 0), "load");
        actionMap.put("load", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { loadFromJson(frame, panel); }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stopPlayback");
        actionMap.put("stopPlayback", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { stopPlayback(); }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0), "quit");
        actionMap.put("quit", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { System.exit(0); }
        });

    }

    private static String getStatusText() {
        if (isPlaying) return "▶️ Воспроизведение...";
        if (isRecording) return "🔴 ЗАПИСЬ АКТИВНА";
        return "⏸ Ожидание (" + actions.size() + " событий)";
    }

    private static void toggleRecording(JPanel panel) {
        isRecording = !isRecording;
        if (isRecording) {
            actions.clear();
            startTime = System.currentTimeMillis();
            System.out.println("🟢 Запись начата");
        } else {
            System.out.println("⏹ Запись остановлена. Сохранено: " + actions.size());
        }
        panel.repaint();
    }

    private static void stopPlayback() {
        if (isPlaying) {
            isStoppingPlayback = true;
            System.out.println("⏹ Воспроизведение остановлено.");
        }
    }

    private static void recordAction(MouseEvent e, ActionType type, int button) {
        if (!isRecording) return;
        long offset = System.currentTimeMillis() - startTime;
        Point screenPos = MouseInfo.getPointerInfo().getLocation();

        // Фильтр микро-дрожаний
        if (type == ActionType.MOVE && !actions.isEmpty()) {
            MouseAction last = actions.get(actions.size() - 1);
            if (Math.abs(screenPos.x - last.x) < 3 && Math.abs(screenPos.y - last.y) < 3) return;
        }

        int mask = 0;
        if (type != ActionType.MOVE) {
            mask = switch (button) {
                case MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
                case MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
                case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
                default -> 0;
            };
        }
        actions.add(new MouseAction(offset, screenPos.x, screenPos.y, type, mask));
    }

    // 📥 Загрузка из JSON
    private static void loadFromJson(JFrame parent, JPanel panel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON Macro", "json"));
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Reader reader = new FileReader(chooser.getSelectedFile())) {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<MouseAction>>(){}.getType();
                List<MouseAction> loaded = gson.fromJson(reader, listType);

                if (loaded != null && !loaded.isEmpty()) {
                    isRecording = false;
                    actions.clear();
                    actions.addAll(loaded);
                    System.out.println("📂 Загружено " + actions.size() + " действий.");
                } else {
                    System.out.println("⚠️ Файл пуст или повреждён.");
                }
            } catch (Exception ex) {
                System.err.println("❌ Ошибка загрузки: " + ex.getMessage());
            }
            panel.repaint();
        }
    }

    // 📤 Сохранение в JSON
    private static void saveToJson(JFrame parent) {
        if (actions.isEmpty()) {
            System.out.println("⚠️ Нечего сохранять.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON Macro", "json"));
        chooser.setSelectedFile(new File("mouse_macro.json"));

        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            try (Writer writer = new FileWriter(chooser.getSelectedFile())) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(actions, writer);
                System.out.println("💾 Сохранено в: " + chooser.getSelectedFile().getAbsolutePath());
            } catch (IOException ex) {
                System.err.println("❌ Ошибка сохранения: " + ex.getMessage());
            }
        }
    }

    // ▶️ Воспроизведение (в отдельном потоке, чтобы не блокировать UI)
    private static void playback() {
        isPlaying = true;
        isStoppingPlayback = false;
        System.out.println("▶️ Начинаю воспроизведение...");
        try {
            Robot robot = new Robot();
            long lastTime = 0;
            List<MouseAction> snapshot = new ArrayList<>(actions); // Копия для потокобезопасности

            for (MouseAction a : snapshot) {
                if (isStoppingPlayback) break;

                long delay = a.timeOffset - lastTime;
                if (delay > 0) {
                    // Разбиваем ожидание на чанки по 50мс для мгновенной реакции на Esc
                    long remaining = delay;
                    while (remaining > 0 && !isStoppingPlayback) {
                        long sleepMs = Math.min(remaining, 50);
                        Thread.sleep(sleepMs);
                        remaining -= sleepMs;
                    }
                }
                if (isStoppingPlayback) break;

                lastTime = a.timeOffset;
                robot.mouseMove(a.x, a.y);
                if (a.type == ActionType.PRESS) robot.mousePress(a.buttonMask);
                else if (a.type == ActionType.RELEASE) robot.mouseRelease(a.buttonMask);
            }
            System.out.println(isStoppingPlayback ? "⏹ Воспроизведение прервано." : "✅ Воспроизведение завершено.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⏹ Воспроизведение прервано.");
        } catch (AWTException e) {
            e.printStackTrace();
        } finally {
            isPlaying = false;
            isStoppingPlayback = false;
        }
    }
}
