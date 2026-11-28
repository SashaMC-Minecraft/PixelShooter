import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * 像素射击游戏 v2.1（单文件、零依赖、JDK 8+ 即可运行）
 *
 * v2.1 新增：
 *   - 标题界面：像素大标题、菜单按钮（单人 / 多人·实验性 / 设置 / 退出）
 *   - 右上角地球图标一键切换语言：简体中文 / English / 日本語
 *   - 多存档：3 个存档位各自记录最高分，可删除
 *   - 设置持久化（语言、静音）到 ~/.pixelshooter/config.properties
 *   - 右下角 Copyright (C) SashaMC 2026
 *
 * 编译运行：
 *   javac -encoding UTF-8 PixelShooter.java
 *   java PixelShooter
 *
 * 操作：菜单 ↑/↓ + Enter 或鼠标点击；游戏内 ←→/AD 移动、空格发射、P 暂停、R 重开、M 静音
 */
public class PixelShooter extends JPanel implements ActionListener, KeyListener {

    static final int W = 200, H = 150, SCALE = 4;
    static final String VERSION = "v2.1";

    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private static final Random rnd = new Random();
    private final Set<Integer> pressed = new HashSet<>();
    private boolean upHeld, downHeld, enterHeld, escHeld, delHeld, muteHeld, pauseHeld, restartHeld, f11Held;
    private final Font uiFont = new Font("SansSerif", Font.PLAIN, 9);
    private final Font uiBold = new Font("SansSerif", Font.BOLD, 9);
    private final Sfx sfx = new Sfx();

    private enum State { TITLE, SAVESEL, OPTIONS, MULTI, PLAYING, PAUSED, OVER }
    private State state = State.TITLE;

    // ---------- 本地化 ----------
    static final String[] LANG_NAMES = {"简体中文", "English", "日本語"};
    static final String[] LANG_TAGS = {"中", "EN", "あ"};
    private int lang;                       // 0=zh 1=en 2=ja
    private static final Map<String, String[]> S = new HashMap<>();
    private static void put(String k, String zh, String en, String ja) {
        S.put(k, new String[]{zh, en, ja});
    }
    static {
        put("single", "单人游戏", "Single Player", "シングルプレイ");
        put("multi", "多人游戏", "Multiplayer", "マルチプレイ");
        put("exp", "实验性", "Experimental", "実験的");
        put("options", "设置选项", "Options", "オプション");
        put("quit", "退出游戏", "Quit", "終了");
        put("saves", "选择存档", "Select Save", "セーブ選択");
        put("save", "存档", "Save", "セーブ");
        put("best", "最高分", "Best", "ハイスコア");
        put("empty", "空", "Empty", "なし");
        put("back", "返回", "Back", "戻る");
        put("language", "语言", "Language", "言語");
        put("sound", "音效", "Sound", "サウンド");
        put("fullscreen", "全屏", "Fullscreen", "全画面");
        put("on", "开", "ON", "オン");
        put("off", "关", "OFF", "オフ");
        put("notice", "实验性功能，敬请期待", "Experimental - coming soon", "実験中の機能、お楽しみに");
        put("anyback", "按任意键或点击返回", "Press any key or click to go back", "キークリックで戻る");
        put("paused", "已暂停", "PAUSED", "一時停止");
        put("resume", "P 继续", "P Resume", "P 再開");
        put("restart", "R 重新开始", "R Restart", "R やり直し");
        put("over", "游戏结束", "GAME OVER", "ゲームオーバー");
        put("score", "分数", "SCORE", "スコア");
        put("newrec", "新纪录！", "NEW RECORD!", "新記録!");
        put("pressR", "按 R 重新开始", "Press R to restart", "Rキーで再開");
        put("pressEsc", "按 ESC 回标题", "Press Esc for title", "Escでタイトルへ");
        put("hintdel", "选中后按 Delete 或点 X 删除存档", "Delete key or X removes selected save", "DeleteまたはXで削除");
        put("hintmouse", "鼠标或 ↑↓ + Enter 操作", "Mouse or ↑↓ + Enter", "マウス/↑↓+Enter");
        put("del", "删除", "Delete", "削除");
        put("new", "创建新存档", "New Save", "新規セーブ");
    }
    private String t(String k) {
        String[] a = S.get(k);
        return a == null ? k : a[lang];
    }

    // ---------- 存档（game/saves，无限数量）----------
    private static final class SaveGame {
        final int id;
        int best;
        SaveGame(int id, int best) { this.id = id; this.best = best; }
    }
    private final List<SaveGame> saves = new ArrayList<>();
    private int saveSel, menuSel, optSel;      // saveSel = 列表行号
    private int saveScroll;                     // 视口首行
    private int currentSave = -1;               // 进行中的存档行号
    private static final int SAVE_ROWS = 4;

    // ---------- 实体（同 v2.0）----------
    private static final int T_GRUNT = 0, T_DARTER = 1, T_TANK = 2, T_GUNNER = 3, T_BOSS = 4;
    private static final int[] EW = {9, 7, 13, 10, 26};
    private static final int[] EH = {8, 6, 11, 9, 12};
    private static final int[] E_SCORE = {10, 15, 25, 20, 300};

    private static final class Bullet {
        double x, y, vx, vy;
        Bullet(double x, double y, double vx, double vy) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; }
    }
    private static final class Enemy {
        double x, y, speed;
        final int type, seed;
        int hp, fireCd, hitFlash;
        Enemy(int type, double x, double speed, int hp) {
            this.type = type; this.x = x; this.y = -EH[type] - 2; this.speed = speed;
            this.hp = hp; this.seed = rnd.nextInt(1000); this.fireCd = 40 + rnd.nextInt(60);
        }
    }
    private static final class Particle {
        double x, y, vx, vy;
        int life;
        final Color c;
        Particle(double x, double y, double vx, double vy, int life, Color c) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.life = life; this.c = c;
        }
    }
    private static final class PowerUp {
        double x, y;
        final int type;
        PowerUp(double x, double y, int type) { this.x = x; this.y = y; this.type = type; }
    }
    private static final char[] PU_LETTER = {'W', 'R', 'S', 'B', '+'};
    private static final Color[] PU_COLOR = {
            new Color(255, 235, 90), new Color(90, 200, 255),
            new Color(120, 255, 160), new Color(255, 140, 60), new Color(255, 90, 130)};

    private final List<Bullet> shots = new ArrayList<>();
    private final List<Bullet> eshots = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<PowerUp> powerups = new ArrayList<>();
    private final int[][] stars = new int[48][3];

    private int playerX = W / 2 - 5;
    private static final int PLAYER_Y = H - 16, PLAYER_W = 10, PLAYER_H = 8;

    private int score, lives, level, cooldown, tick;
    private int weapon = 1;
    private int rapidTimer, shieldTimer, invTimer, combo, comboTimer, bossWarn, bombFx, shake;
    private boolean newRecord;

    // 窗口 / 全屏
    private JFrame frame;
    private boolean fullscreen;
    private Rectangle windowedBounds;

    // 崩溃日志（game/crash-log/log-年-月-日-时-分.log）
    private PrintStream logStream;
    private Path logPath;

    // 鼠标
    private int mx = -1, my = -1;

    public PixelShooter() {
        setPreferredSize(new Dimension(W * SCALE, H * SCALE));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handleClick(lx(e.getX()), ly(e.getY())); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { mx = lx(e.getX()); my = ly(e.getY()); }
            @Override public void mouseDragged(MouseEvent e) { mx = -1; my = -1; }
        });
        for (int i = 0; i < stars.length; i++) {
            stars[i] = new int[]{rnd.nextInt(W), rnd.nextInt(H), 1 + rnd.nextInt(2)};
        }
        loadData();
        initCrashLog();
        if (fullscreen) {
            SwingUtilities.invokeLater(this::setFullscreen);
        }
        reset();
        new javax.swing.Timer(1000 / 60, this).start();
    }

    // ---------- 崩溃日志 game/crash-log/log-YYYY-MM-dd-HH-mm.log ----------

    private void initCrashLog() {
        try {
            Path dir = Paths.get("game", "crash-log").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date());
            logPath = dir.resolve("log-" + stamp + ".log");
            logStream = new PrintStream(new FileOutputStream(logPath.toFile(), true), true, "UTF-8");
            log("PixelShooter " + VERSION + " started | java " + System.getProperty("java.version")
                    + " | " + System.getProperty("os.name") + " | cwd " + Paths.get("").toAbsolutePath());
            log("log file: " + logPath);
        } catch (Exception e) {
            logStream = null;   // 日志不可用不影响游戏
        }
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try {
                log("UNCAUGHT on thread " + t.getName() + ": " + ex);
                if (logStream != null) ex.printStackTrace(logStream);
                ex.printStackTrace();
            } catch (Exception ignored) { }
        });
    }

    private void log(String msg) {
        if (logStream != null) {
            logStream.println("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg);
            logStream.flush();
        }
    }

    private void closeLog(String msg) {
        try { log(msg); } catch (Exception ignored) { }
        try { if (logStream != null) logStream.close(); } catch (Exception ignored) { }
        logStream = null;
    }

    private void reset() {
        shots.clear(); eshots.clear(); enemies.clear(); particles.clear(); powerups.clear();
        score = 0; lives = 3; level = 1; cooldown = 0; tick = 0;
        weapon = 1; rapidTimer = 0; shieldTimer = 0; invTimer = 0;
        combo = 0; comboTimer = 0; bossWarn = 0; bombFx = 0; shake = 0; newRecord = false;
        playerX = W / 2 - 5;
    }

    // ---------- 数据持久化 ----------

    private static Path configDir() {
        return Paths.get(System.getProperty("user.home"), ".pixelshooter");
    }

    /** 存档目录：运行目录下的 game/saves */
    private static Path savesDir() {
        return Paths.get("game", "saves").toAbsolutePath().normalize();
    }

    private void loadData() {
        // 1) 全局设置（语言 / 静音）
        Properties p = new Properties();
        try {
            Files.createDirectories(configDir());
            Path f = configDir().resolve("config.properties");
            if (Files.exists(f)) {
                try (InputStream in = Files.newInputStream(f)) { p.load(in); }
                lang = clamp(parseInt(p.getProperty("lang", "0"), 0), 0, 2);
                sfx.setMuted(parseInt(p.getProperty("mute", "0"), 0) != 0);
                fullscreen = parseInt(p.getProperty("fullscreen", "0"), 0) != 0;
            }
        } catch (Exception ignored) { }

        // 2) 存档：game/saves/saves.dat，每行 "id=best"
        try {
            Files.createDirectories(savesDir());
            Path f = savesFile();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    int id = parseInt(line.substring(0, eq), -1);
                    int best = parseInt(line.substring(eq + 1), 0);
                    if (id > 0) saves.add(new SaveGame(id, Math.max(0, best)));
                }
            }
            if (saves.isEmpty()) {
                // 兼容迁移：v2.0 单文件最高分 / v2.1 三档位 -> 存档 1
                int carried = 0;
                Path old = Paths.get(System.getProperty("user.home"), ".pixelshooter_hi.txt");
                if (Files.exists(old)) {
                    carried = parseInt(new String(Files.readAllBytes(old), StandardCharsets.UTF_8).trim(), 0);
                } else {
                    carried = parseInt(p.getProperty("best0", "0"), 0);
                }
                saves.add(new SaveGame(1, Math.max(0, carried)));
                saveData();
            }
        } catch (Exception ignored) {
            saves.add(new SaveGame(1, 0));
        }
        sortSaves();
    }

    private static Path savesFile() {
        return savesDir().resolve("saves.dat");
    }

    private void sortSaves() {
        saves.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    private void saveData() {
        Properties p = new Properties();
        p.setProperty("lang", Integer.toString(lang));
        p.setProperty("mute", sfx.isMuted() ? "1" : "0");
        p.setProperty("fullscreen", fullscreen ? "1" : "0");
        try {
            Files.createDirectories(configDir());
            try (OutputStream out = Files.newOutputStream(configDir().resolve("config.properties"))) {
                p.store(out, "PixelShooter " + VERSION + " config");
            }
        } catch (Exception ignored) { }

        StringBuilder sb = new StringBuilder();
        for (SaveGame s : saves) sb.append(s.id).append('=').append(s.best).append('\n');
        try {
            Files.createDirectories(savesDir());
            Files.write(savesFile(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** 在 game/saves 下新建一个从零开始的存档，返回行号 */
    private int createSave() {
        int maxId = 0;
        for (SaveGame s : saves) maxId = Math.max(maxId, s.id);
        saves.add(new SaveGame(maxId + 1, 0));
        sortSaves();
        saveData();
        for (int i = 0; i < saves.size(); i++) if (saves.get(i).id == maxId + 1) return i;
        return saves.size() - 1;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------- 逻辑 ----------

    @Override
    public void actionPerformed(ActionEvent e) {
        tick++;
        boolean left = pressed.contains(KeyEvent.VK_LEFT) || pressed.contains(KeyEvent.VK_A);
        boolean right = pressed.contains(KeyEvent.VK_RIGHT) || pressed.contains(KeyEvent.VK_D);
        boolean fire = pressed.contains(KeyEvent.VK_SPACE);

        boolean m = pressed.contains(KeyEvent.VK_M);
        if (m && !muteHeld) { sfx.toggleMute(); saveData(); }
        muteHeld = m;

        boolean up = pressed.contains(KeyEvent.VK_UP) || pressed.contains(KeyEvent.VK_W);
        boolean down = pressed.contains(KeyEvent.VK_DOWN) || pressed.contains(KeyEvent.VK_S);
        boolean enter = pressed.contains(KeyEvent.VK_ENTER);
        boolean esc = pressed.contains(KeyEvent.VK_ESCAPE);
        boolean del = pressed.contains(KeyEvent.VK_DELETE);
        boolean pause = pressed.contains(KeyEvent.VK_P);
        boolean restart = pressed.contains(KeyEvent.VK_R);
        boolean f11 = pressed.contains(KeyEvent.VK_F11);
        if (f11 && !f11Held) toggleFullscreen();
        f11Held = f11;
        // 全屏时，游戏内 ESC 优先用于退出全屏
        if (esc && !escHeld && fullscreen && state == State.PLAYING) {
            toggleFullscreen();
            escHeld = esc;
            repaint();
            return;
        }

        if (state != State.PLAYING) {
            if (up && !upHeld) navMenu(-1);
            if (down && !downHeld) navMenu(1);
            if (enter && !enterHeld) activate();
            if (del && !delHeld) deleteSlot();
        }
        upHeld = up; downHeld = down; enterHeld = enter; delHeld = del;

        switch (state) {
            case PLAYING: {
                if (pause && !pauseHeld) state = State.PAUSED;
                if (restart && !restartHeld) reset();
                updateGame(left, right, fire);
                break;
            }
            case PAUSED: {
                if (restart && !restartHeld) { reset(); state = State.PLAYING; }
                else if ((pause && !pauseHeld) || (esc && !escHeld)) state = State.PLAYING;
                break;
            }
            case OVER: {
                if (restart && !restartHeld) { reset(); state = State.PLAYING; }
                else if (esc && !escHeld) state = State.TITLE;
                break;
            }
            case MULTI: {
                if (esc && !escHeld) state = State.TITLE;
                break;
            }
            default: {
                if (state == State.SAVESEL && esc && !escHeld) state = State.TITLE;
                if (state == State.OPTIONS && esc && !escHeld) { state = State.TITLE; saveData(); }
                break;
            }
        }
        pauseHeld = pause; restartHeld = restart; escHeld = esc;

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle pa = particles.get(i);
            pa.x += pa.vx; pa.y += pa.vy; pa.vy += 0.04;
            if (--pa.life <= 0) particles.remove(i);
        }
        for (int[] s : stars) {
            s[1] += state == State.PLAYING ? s[2] : 1;
            if (s[1] >= H) { s[1] = 0; s[0] = rnd.nextInt(W); }
        }
        if (bombFx > 0) bombFx--;
        if (shake > 0) shake--;
        repaint();
    }

    private int menuCount() {
        switch (state) {
            case TITLE: return 4;
            case SAVESEL: return saves.size();
            case OPTIONS: return 4;
            default: return 0;
        }
    }

    private void navMenu(int d) {
        int n = menuCount();
        if (n == 0) return;
        if (state == State.TITLE) menuSel = (menuSel + d + n) % n;
        else if (state == State.SAVESEL) {
            saveSel = clamp(saveSel + d, 0, n - 1);
            if (saveSel < saveScroll) saveScroll = saveSel;
            if (saveSel >= saveScroll + SAVE_ROWS) saveScroll = saveSel - SAVE_ROWS + 1;
        }
        else if (state == State.OPTIONS) optSel = (optSel + d + n) % n;
    }

    private void activate() {
        switch (state) {
            case TITLE:
                if (menuSel == 0) { saveSel = Math.min(saveSel, Math.max(0, saves.size() - 1)); state = State.SAVESEL; }
                else if (menuSel == 1) state = State.MULTI;
                else if (menuSel == 2) { optSel = 0; state = State.OPTIONS; }
                else quitGame();
                break;
            case SAVESEL:
                if (saves.isEmpty() || saveSel >= saves.size()) return;
                currentSave = saveSel;
                reset();                     // 每个存档都从零开始
                state = State.PLAYING;
                break;
            case OPTIONS:
                if (optSel == 0) { lang = (lang + 1) % 3; saveData(); }
                else if (optSel == 1) { sfx.toggleMute(); saveData(); }
                else if (optSel == 2) { toggleFullscreen(); }
                else { state = State.TITLE; saveData(); }
                break;
            case MULTI:
                state = State.TITLE;
                break;
            default:
                break;
        }
    }

    private void deleteSlot() {
        if (state == State.SAVESEL && !saves.isEmpty()) {
            saveSel = clamp(saveSel, 0, saves.size() - 1);
            saves.remove(saveSel);
            if (saveSel >= saves.size()) saveSel = Math.max(0, saves.size() - 1);
            if (currentSave >= saves.size()) currentSave = -1;
            saveData();
            sfx.play(Sound.POWER);
        }
    }

    private void newSave() {
        int idx = createSave();
        saveSel = idx;
        if (saveSel >= saveScroll + SAVE_ROWS) saveScroll = saveSel - SAVE_ROWS + 1;
        if (saveSel < saveScroll) saveScroll = saveSel;
        sfx.play(Sound.POWER);
    }

    private int currentBest() {
        return (currentSave >= 0 && currentSave < saves.size()) ? saves.get(currentSave).best : 0;
    }

    private void quitGame() {
        saveData();
        closeLog("quit by user");
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) w.dispose();
        System.exit(0);
    }

    // ---------- 全屏 ----------

    /** 逻辑坐标换算：兼容窗口与全屏两种缩放 */
    private int lx(int px) { return getWidth() > 0 ? px * W / getWidth() : -1; }
    private int ly(int py) { return getHeight() > 0 ? py * H / getHeight() : -1; }

    void attachFrame(JFrame f) { this.frame = f; }

    void toggleFullscreen() {
        fullscreen = !fullscreen;
        saveData();
        if (fullscreen) setFullscreen();
        else clearFullscreen();
        log("fullscreen " + (fullscreen ? "ON" : "OFF"));
    }

    private void setFullscreen() {
        if (frame == null || fullscreenGuard()) return;
        GraphicsDevice dev = frame.getGraphicsConfiguration().getDevice();
        if (!dev.isFullScreenSupported()) {
            fullscreen = false;
            log("fullscreen not supported by device, fallback to windowed");
            return;
        }
        try {
            if (!frame.isDisplayable()) {
                windowedBounds = null;
            } else {
                windowedBounds = frame.getBounds();
            }
            frame.setVisible(false);
            dev.setFullScreenWindow(frame);
            frame.setVisible(true);
            requestFocusInWindow();
        } catch (Exception e) {
            log("enter fullscreen failed: " + e);
            fullscreen = false;
            saveData();
        }
    }

    private boolean fullscreenGuard() {
        return frame == null;
    }

    private void clearFullscreen() {
        if (frame == null) return;
        GraphicsDevice dev = frame.getGraphicsConfiguration().getDevice();
        try {
            dev.setFullScreenWindow(null);
            frame.setVisible(false);
            if (windowedBounds != null) {
                frame.setBounds(windowedBounds);
            } else {
                frame.setSize(W * SCALE, H * SCALE);
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
            requestFocusInWindow();
        } catch (Exception e) {
            log("exit fullscreen failed: " + e);
        }
    }

    // ---------- 鼠标 ----------

    private void handleClick(int x, int y) {
        switch (state) {
            case TITLE: {
                if (globeRect().contains(x, y)) {
                    lang = (lang + 1) % 3;
                    saveData();
                    sfx.play(Sound.POWER);
                    return;
                }
                Rectangle[] rs = titleBtnRects();
                for (int i = 0; i < rs.length; i++) {
                    if (rs[i].contains(x, y)) { menuSel = i; activate(); return; }
                }
                break;
            }
            case SAVESEL: {
                for (int i = 0; i < SAVE_ROWS; i++) {
                    int idx = saveScroll + i;
                    if (idx >= saves.size()) break;
                    if (saveRowRect(i).contains(x, y)) { saveSel = idx; activate(); return; }
                    if (delRect(i).contains(x, y)) { saveSel = idx; deleteSlot(); return; }
                }
                if (upBtnRect().contains(x, y)) { scrollSaves(-1); return; }
                if (dnBtnRect().contains(x, y)) { scrollSaves(1); return; }
                if (newBtnRect().contains(x, y)) { newSave(); return; }
                if (backRect().contains(x, y)) state = State.TITLE;
                break;
            }
            case OPTIONS: {
                Rectangle[] rows = optRowRects();
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i].contains(x, y)) { optSel = i; activate(); return; }
                }
                break;
            }
            case MULTI:
                state = State.TITLE;
                break;
            case OVER:
                if (new Rectangle(40, H / 2 + 10, W - 80, 14).contains(x, y)) { reset(); state = State.PLAYING; }
                break;
            default:
                break;
        }
    }

    private Rectangle globeRect() { return new Rectangle(W - 18, 2, 14, 14); }

    private Rectangle[] titleBtnRects() {
        Rectangle[] rs = new Rectangle[4];
        for (int i = 0; i < 4; i++) rs[i] = new Rectangle(W / 2 - 48, 66 + i * 15, 96, 13);
        return rs;
    }

    // 存档页几何：4 行可见、上下滚动、新建 / 返回
    private Rectangle saveRowRect(int vis) { return new Rectangle(20, 34 + vis * 13, 140, 11); }
    private Rectangle delRect(int vis) { return new Rectangle(160, 34 + vis * 13, 10, 11); }
    private Rectangle upBtnRect() { return new Rectangle(174, 34, 12, 12); }
    private Rectangle dnBtnRect() { return new Rectangle(174, 73, 12, 12); }
    private Rectangle newBtnRect() { return new Rectangle(20, 92, 78, 13); }
    private Rectangle backBtn2Rect() { return new Rectangle(102, 92, 78, 13); }

    private void scrollSaves(int d) {
        int maxScroll = Math.max(0, saves.size() - SAVE_ROWS);
        saveScroll = clamp(saveScroll + d, 0, maxScroll);
        if (!saves.isEmpty()) saveSel = clamp(saveSel, saveScroll, Math.min(saveScroll + SAVE_ROWS - 1, saves.size() - 1));
    }

    private Rectangle backRect() { return new Rectangle(W / 2 - 24, 100, 48, 13); }

    private Rectangle[] optRowRects() {
        Rectangle[] rs = new Rectangle[4];
        for (int i = 0; i < 4; i++) rs[i] = new Rectangle(30, 48 + i * 16, W - 60, 14);
        return rs;
    }

    // ---------- 游戏逻辑（v2.0 延续）----------

    private void updateGame(boolean left, boolean right, boolean fire) {
        if (rapidTimer > 0) rapidTimer--;
        if (shieldTimer > 0) shieldTimer--;
        if (invTimer > 0) invTimer--;
        if (bossWarn > 0) bossWarn--;
        if (comboTimer > 0 && --comboTimer == 0) combo = 0;

        level = 1 + score / 120;

        if (left) playerX -= 2;
        if (right) playerX += 2;
        playerX = Math.max(2, Math.min(W - PLAYER_W - 2, playerX));

        if (fire && cooldown <= 0) {
            int cx = playerX + PLAYER_W / 2;
            int y = PLAYER_Y - 4;
            if (weapon == 1) {
                shots.add(new Bullet(cx - 1, y, 0, -3.2));
            } else if (weapon == 2) {
                shots.add(new Bullet(cx - 4, y, 0, -3.2));
                shots.add(new Bullet(cx + 2, y, 0, -3.2));
            } else {
                shots.add(new Bullet(cx - 1, y, 0, -3.4));
                shots.add(new Bullet(cx - 3, y, -0.9, -3.0));
                shots.add(new Bullet(cx + 1, y, 0.9, -3.0));
            }
            cooldown = rapidTimer > 0 ? 4 : 9;
            sfx.play(Sound.SHOOT);
        }
        if (cooldown > 0) cooldown--;

        for (int i = shots.size() - 1; i >= 0; i--) {
            Bullet b = shots.get(i);
            b.x += b.vx; b.y += b.vy;
            if (b.y < -6 || b.x < -4 || b.x > W + 4) shots.remove(i);
        }

        boolean bossAlive = false;
        for (Enemy en : enemies) if (en.type == T_BOSS) { bossAlive = true; break; }
        if (!bossAlive && tick > 600 && tick % 1800 == 0) {
            enemies.add(new Enemy(T_BOSS, W / 2 - EW[T_BOSS] / 2, 0.5, bossHpMax()));
            bossWarn = 90;
            sfx.play(Sound.BOOM);
        }
        if (!bossAlive || tick % 240 == 0) {
            int interval = Math.max(16, 70 - level * 6);
            if (tick % interval == 0) spawnEnemy();
        }

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            if (en.hitFlash > 0) en.hitFlash--;
            en.y += en.speed;

            switch (en.type) {
                case T_GRUNT:
                    en.x += Math.sin((tick + en.seed) * 0.06) * 0.6;
                    break;
                case T_DARTER:
                    en.x += Math.sin((tick + en.seed) * 0.2) * 1.4;
                    break;
                case T_TANK:
                    en.x += Math.sin((tick + en.seed) * 0.03) * 0.3;
                    break;
                case T_GUNNER:
                    en.x += Math.sin((tick + en.seed) * 0.05) * 0.5;
                    if (en.y > 10 && --en.fireCd <= 0) {
                        eshots.add(new Bullet(en.x + EW[T_GUNNER] / 2, en.y + EH[T_GUNNER], 0, 1.7));
                        en.fireCd = 90 + rnd.nextInt(40);
                    }
                    break;
                case T_BOSS:
                    if (en.y < 14) { en.y += 0.5; }
                    else {
                        en.x += Math.sin((tick + en.seed) * 0.035) * 1.1;
                        if (--en.fireCd <= 0) {
                            double bx = en.x + EW[T_BOSS] / 2;
                            eshots.add(new Bullet(bx - 2, en.y + EH[T_BOSS], -0.7, 1.5));
                            eshots.add(new Bullet(bx, en.y + EH[T_BOSS], 0, 1.7));
                            eshots.add(new Bullet(bx + 2, en.y + EH[T_BOSS], 0.7, 1.5));
                            en.fireCd = 55;
                        }
                    }
                    break;
            }
            en.x = Math.max(2, Math.min(W - EW[en.type] - 2, en.x));

            boolean dead = false;
            for (int j = shots.size() - 1; j >= 0; j--) {
                Bullet b = shots.get(j);
                if (hit(b.x, b.y, 2, 4, en.x, en.y, EW[en.type], EH[en.type])) {
                    shots.remove(j);
                    if (--en.hp <= 0) { dead = true; break; }
                    en.hitFlash = 4;
                    burst(b.x, b.y, new Color(255, 220, 140), 3, 1.2);
                }
            }
            if (dead) { killEnemy(en, i); continue; }

            if (hit(playerX, PLAYER_Y, PLAYER_W, PLAYER_H, en.x, en.y, EW[en.type], EH[en.type])) {
                enemies.remove(i);
                if (shieldTimer > 0) {
                    burst(en.x + EW[en.type] / 2, en.y, new Color(120, 255, 160), 12, 2.2);
                    sfx.play(Sound.POWER);
                } else if (invTimer <= 0) {
                    damagePlayer(en.x + EW[en.type] / 2, en.y + 3, new Color(255, 200, 60));
                }
                continue;
            }
            if (en.y > H) {
                enemies.remove(i);
                if (en.type != T_BOSS) {
                    burst(en.x + EW[en.type] / 2, H - 2, new Color(255, 200, 60), 8, 1.8);
                    damagePlayer(en.x, H - 10, new Color(255, 200, 60));
                }
            }
        }

        for (int i = eshots.size() - 1; i >= 0; i--) {
            Bullet b = eshots.get(i);
            b.x += b.vx; b.y += b.vy;
            if (b.y > H + 4 || b.x < -4 || b.x > W + 4) { eshots.remove(i); continue; }
            if (hit(playerX, PLAYER_Y, PLAYER_W, PLAYER_H, b.x, b.y, 2, 3)) {
                eshots.remove(i);
                if (shieldTimer > 0) {
                    burst(b.x, b.y, new Color(120, 255, 160), 6, 1.8);
                } else if (invTimer <= 0) {
                    damagePlayer(b.x, b.y, new Color(255, 120, 120));
                }
            }
        }

        for (int i = powerups.size() - 1; i >= 0; i--) {
            PowerUp pu = powerups.get(i);
            pu.y += 0.8;
            if (pu.y > H) { powerups.remove(i); continue; }
            if (hit(playerX, PLAYER_Y - 2, PLAYER_W, PLAYER_H + 2, pu.x, pu.y, 8, 8)) {
                powerups.remove(i);
                applyPowerUp(pu.type);
            }
        }
    }

    private int bossHpMax() { return Math.min(14 + level * 4, 40); }

    private void spawnEnemy() {
        int type;
        int roll = rnd.nextInt(100);
        if (level >= 4 && roll < 14) type = T_TANK;
        else if (level >= 3 && roll < 30) type = T_GUNNER;
        else if (level >= 2 && roll < 50) type = T_DARTER;
        else type = T_GRUNT;

        double base = 0.35 + rnd.nextDouble() * 0.35 + level * 0.10;
        double x = 6 + rnd.nextInt(Math.max(1, W - 12 - EW[type]));
        int hp = type == T_TANK ? 3 : 1;
        if (type == T_DARTER) base += 0.7;
        enemies.add(new Enemy(type, x, base, hp));
    }

    private void killEnemy(Enemy en, int index) {
        enemies.remove(index);
        combo++; comboTimer = 90;
        int mult = 1 + Math.min(combo / 5, 3);
        score += E_SCORE[en.type] * mult;
        burst(en.x + EW[en.type] / 2, en.y + EH[en.type] / 2,
                en.type == T_BOSS ? new Color(255, 120, 40) : new Color(255, 90, 90),
                en.type == T_BOSS ? 40 : 10, en.type == T_BOSS ? 3.0 : 2.2);
        sfx.play(en.type == T_BOSS ? Sound.BOOM : Sound.HIT);
        if (en.type == T_BOSS) shake = 14;
        int drops = en.type == T_BOSS ? 2 : (rnd.nextDouble() < 0.12 ? 1 : 0);
        for (int d = 0; d < drops; d++) {
            powerups.add(new PowerUp(en.x + rnd.nextInt(6), en.y, rollPowerUp()));
        }
    }

    private int rollPowerUp() {
        int roll = rnd.nextInt(100);
        if (lives < 5 && roll < 10) return 4;
        if (roll < 25) return 3;
        if (roll < 45) return 2;
        if (roll < 70) return 1;
        return 0;
    }

    private void applyPowerUp(int type) {
        sfx.play(Sound.POWER);
        burst(playerX + PLAYER_W / 2, PLAYER_Y, PU_COLOR[type], 12, 2.0);
        switch (type) {
            case 0: if (weapon < 3) weapon++; else score += 50; break;
            case 1: rapidTimer = 480; break;
            case 2: shieldTimer = 600; break;
            case 3: detonate(); break;
            case 4: if (lives < 5) lives++; else score += 100; break;
        }
    }

    private void detonate() {
        bombFx = 10; shake = 10;
        sfx.play(Sound.BOOM);
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            if (en.type == T_BOSS) {
                en.hp -= 6;
                if (en.hp <= 0) killEnemy(en, i);
                continue;
            }
            burst(en.x + EW[en.type] / 2, en.y + EH[en.type] / 2, new Color(255, 180, 80), 8, 2.4);
            enemies.remove(i);
            score += 5;
        }
        eshots.clear();
    }

    private void damagePlayer(double x, double y, Color c) {
        burst(x, y, c, 12, 2.2);
        sfx.play(Sound.LOSE);
        invTimer = 75; shake = 6; combo = 0; comboTimer = 0;
        if (--lives <= 0) {
            lives = 0;
            state = State.OVER;
            if (currentSave >= 0 && currentSave < saves.size() && score > saves.get(currentSave).best) {
                saves.get(currentSave).best = score;
                newRecord = true;
            }
            saveData();
        }
    }

    private static boolean hit(double ax, double ay, double aw, double ah,
                               double bx, double by, double bw, double bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    private void burst(double x, double y, Color c, int count, double spread) {
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(x, y,
                    (rnd.nextDouble() - 0.5) * spread * 2.2,
                    (rnd.nextDouble() - 0.5) * spread * 2.2,
                    12 + rnd.nextInt(12), c));
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(10, 12, 24));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(45, 55, 90));
        for (int[] s : stars) g.fillRect(s[0], s[1], 1, 1);

        switch (state) {
            case TITLE: drawTitle(g); break;
            case SAVESEL: drawSaves(g); break;
            case OPTIONS: drawOptions(g); break;
            case MULTI: drawMulti(g); break;
            default: drawGame(g); break;
        }

        g.dispose();
        Graphics2D g2 = (Graphics2D) gr;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(canvas, 0, 0, getWidth(), getHeight(), null);
    }

    // ---------- 标题界面 ----------

    private void drawTitle(Graphics2D g) {
        drawLogo(g, 16);

        g.setColor(new Color(140, 160, 200));
        g.setFont(uiFont);
        String ver = VERSION + " - " + t("hintmouse");
        center(g, ver, 52, uiFont);

        Rectangle[] rs = titleBtnRects();
        String[] labels = {
                t("single"),
                t("multi") + " (" + t("exp") + ")",
                t("options"),
                t("quit")};
        for (int i = 0; i < 4; i++) {
            boolean hot = i == menuSel || rs[i].contains(mx, my);
            drawButton(g, rs[i], labels[i], hot, i == menuSel);
        }

        drawGlobe(g);

        g.setColor(new Color(120, 130, 160));
        g.setFont(uiFont);
        g.drawString("Copyright (C) SashaMC 2026", W - g.getFontMetrics().stringWidth("Copyright (C) SashaMC 2026") - 4, H - 4);
    }

    private void drawButton(Graphics2D g, Rectangle r, String label, boolean hot, boolean sel) {
        g.setColor(hot ? new Color(40, 90, 60) : new Color(26, 32, 54));
        g.fillRect(r.x, r.y, r.width, r.height);
        g.setColor(hot ? new Color(120, 255, 160) : new Color(80, 95, 140));
        g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        if (sel) {
            g.setColor(new Color(255, 235, 90));
            g.fillRect(r.x + 3, r.y + r.height / 2 - 1, 2, 2);
        }
        g.setColor(hot ? Color.WHITE : new Color(190, 200, 225));
        g.setFont(hot ? uiBold : uiFont);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + (r.height + fm.getAscent() - 2) / 2);
    }

    /** 右上角手绘像素地球：点击切语言 */
    private void drawGlobe(Graphics2D g) {
        Rectangle r = globeRect();
        boolean hot = r.contains(mx, my);
        int cx = r.x + r.width / 2, cy = r.y + r.height / 2, rad = 6;
        g.setColor(hot ? new Color(140, 200, 255) : new Color(70, 110, 170));
        g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
        g.setColor(new Color(60, 160, 80));                 // 大陆块
        g.fillRect(cx - 4, cy - 3, 3, 2);
        g.fillRect(cx + 1, cy - 1, 2, 3);
        g.fillRect(cx - 2, cy + 2, 2, 2);
        g.setColor(new Color(230, 245, 255, hot ? 200 : 130)); // 经线
        g.drawOval(cx - rad, cy - rad, rad * 2 - 1, rad * 2 - 1);
        g.drawOval(cx - 2, cy - rad, 5, rad * 2 - 1);
        g.drawLine(cx - rad, cy, cx + rad, cy);
        g.setColor(hot ? Color.WHITE : new Color(180, 200, 230));
        g.setFont(uiFont);
        FontMetrics fm2 = g.getFontMetrics();
        g.drawString(LANG_TAGS[lang], r.x + r.width / 2 - fm2.stringWidth(LANG_TAGS[lang]) / 2, r.y + r.height + 8);
    }

    /** 像素大标题：自绘 5x7 位图字体 + 流动彩虹色 */
    private static final Map<Character, String[]> PIXFONT = new HashMap<>();
    static {
        PIXFONT.put('P', new String[]{"11110", "10001", "10001", "11110", "10000", "10000", "10000"});
        PIXFONT.put('I', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "11111"});
        PIXFONT.put('X', new String[]{"10001", "10001", "01010", "00100", "01010", "10001", "10001"});
        PIXFONT.put('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"});
        PIXFONT.put('L', new String[]{"10000", "10000", "10000", "10000", "10000", "10000", "11111"});
        PIXFONT.put('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"});
        PIXFONT.put('H', new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"});
        PIXFONT.put('O', new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"});
        PIXFONT.put('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"});
        PIXFONT.put('R', new String[]{"11110", "10001", "10001", "11110", "10100", "10010", "10001"});
    }

    private void drawLogo(Graphics2D g, int top) {
        String text = "PIXELSHOOTER";
        int scale = 2, gap = 1;
        int glyphW = 5 * scale, glyphH = 7 * scale;
        int totalW = text.length() * (glyphW + gap * scale) - gap * scale;
        int x0 = (W - totalW) / 2;
        for (int c = 0; c < text.length(); c++) {
            String[] rows = PIXFONT.get(text.charAt(c));
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if (rows[row].charAt(col) != '1') continue;
                    int px = x0 + c * (glyphW + gap * scale) + col * scale;
                    int py = top + row * scale;
                    float hue = ((px + c * 4 + tick * 0.5f) % 72f) / 72f;
                    g.setColor(Color.getHSBColor(hue, 0.75f, 1.0f));
                    g.fillRect(px, py, scale, scale);
                    g.setColor(new Color(255, 255, 255, 90));
                    g.fillRect(px, py, 1, 1);
                }
            }
        }
    }

    // ---------- 存档选择 ----------

    private void drawSaves(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(uiBold);
        center(g, t("saves"), 24, uiBold);

        for (int vis = 0; vis < SAVE_ROWS; vis++) {
            int idx = saveScroll + vis;
            if (idx >= saves.size()) break;
            SaveGame sv = saves.get(idx);
            Rectangle rr = saveRowRect(vis);
            boolean hot = idx == saveSel || rr.contains(mx, my);
            g.setColor(hot ? new Color(40, 60, 110) : new Color(24, 30, 52));
            g.fillRect(rr.x, rr.y, rr.width, rr.height);
            g.setColor(hot ? new Color(150, 200, 255) : new Color(70, 85, 125));
            g.drawRect(rr.x, rr.y, rr.width - 1, rr.height - 1);

            g.setFont(hot ? uiBold : uiFont);
            g.setColor(Color.WHITE);
            g.drawString(t("save") + " " + sv.id, rr.x + 5, rr.y + 9);
            g.setColor(sv.best > 0 ? new Color(255, 235, 90) : new Color(120, 130, 160));
            String bs = sv.best > 0 ? Integer.toString(sv.best) : t("empty");
            FontMetrics fm = g.getFontMetrics();
            g.drawString(bs, rr.x + rr.width - fm.stringWidth(bs) - 5, rr.y + 9);

            Rectangle dr = delRect(vis);
            g.setColor(new Color(60, 30, 40));
            g.fillRect(dr.x, dr.y, dr.width, dr.height);
            g.setColor(new Color(255, 100, 110));
            g.drawRect(dr.x, dr.y, dr.width - 1, dr.height - 1);
            g.setFont(uiBold);
            g.drawString("x", dr.x + 3, dr.y + 9);
        }

        drawScrollBtn(g, upBtnRect(), "^", saveScroll > 0);
        drawScrollBtn(g, dnBtnRect(), "v", saveScroll + SAVE_ROWS < saves.size());

        Rectangle nb = newBtnRect();
        drawButton(g, nb, "+ " + t("new"), nb.contains(mx, my), false);
        Rectangle bk = backBtn2Rect();
        drawButton(g, bk, "< " + t("back"), bk.contains(mx, my), false);

        g.setColor(new Color(110, 125, 155));
        g.setFont(uiFont);
        center(g, t("save") + " " + (saves.isEmpty() ? 0 : saves.get(clamp(saveSel, 0, saves.size() - 1)).id)
                + "  (" + saves.size() + ")", 118, uiFont);
        g.setColor(new Color(120, 130, 160));
        center(g, t("hintdel"), 130, uiFont);
    }

    private void drawScrollBtn(Graphics2D g, Rectangle r, String label, boolean enabled) {
        g.setColor(new Color(24, 30, 52));
        g.fillRect(r.x, r.y, r.width, r.height);
        g.setColor(enabled ? new Color(150, 200, 255) : new Color(55, 65, 90));
        g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        if (!enabled) return;
        g.setColor(Color.WHITE);
        g.setFont(uiBold);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + (r.height + fm.getAscent() - 3) / 2);
    }

    // ---------- 设置 ----------

    private void drawOptions(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(uiBold);
        center(g, t("options"), 26, uiBold);

        String[] labels = {
                t("language") + ": " + LANG_NAMES[lang],
                t("sound") + ": " + (sfx.isMuted() ? t("off") : t("on")),
                t("fullscreen") + ": " + (fullscreen ? t("on") : t("off")),
                "< " + t("back")};
        Rectangle[] rows = optRowRects();
        for (int i = 0; i < rows.length; i++) {
            boolean hot = i == optSel || rows[i].contains(mx, my);
            drawButton(g, rows[i], labels[i], hot, i == optSel);
        }
    }

    // ---------- 多人（占位）----------

    private void drawMulti(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(uiBold);
        center(g, t("multi") + " (" + t("exp") + ")", 56, uiBold);
        g.setColor(new Color(255, 200, 90));
        g.setFont(uiFont);
        center(g, t("notice"), 76, uiFont);
        g.setColor(new Color(120, 130, 160));
        center(g, t("anyback"), 100, uiFont);
    }

    // ---------- 游戏画面 ----------

    private void drawGame(Graphics2D g) {
        if (shake > 0) g.translate(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);

        drawEnemies(g);
        drawPowerUps(g);

        g.setColor(new Color(255, 235, 90));
        for (Bullet b : shots) g.fillRect((int) b.x, (int) b.y, 2, 4);
        g.setColor(new Color(255, 100, 220));
        for (Bullet b : eshots) g.fillRect((int) b.x, (int) b.y, 2, 3);

        drawPlayer(g);

        for (Particle p : particles) {
            g.setColor(p.life > 8 ? p.c : new Color(120, 60, 40));
            g.fillRect((int) p.x, (int) p.y, 2, 2);
        }

        if (bossWarn > 0 && (bossWarn / 10) % 2 == 0) {
            g.setColor(new Color(255, 70, 70));
            g.setFont(uiBold);
            center(g, "! BOSS INCOMING !", H / 2 - 26, uiBold);
        }

        drawHud(g);

        if (bombFx > 0) {
            g.setColor(new Color(255, 255, 255, bombFx * 18));
            g.fillRect(-4, -4, W + 8, H + 8);
        }

        if (state == State.PAUSED || state == State.OVER) {
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(-4, -4, W + 8, H + 8);
            if (state == State.PAUSED) {
                g.setColor(new Color(120, 200, 255));
                g.setFont(uiBold);
                center(g, t("paused"), H / 2 - 10, uiBold);
                g.setColor(Color.WHITE);
                g.setFont(uiFont);
                center(g, t("resume") + "   " + t("restart"), H / 2 + 10, uiFont);
            } else {
                g.setColor(new Color(255, 90, 90));
                g.setFont(uiBold);
                center(g, t("over"), H / 2 - 30, uiBold);
                g.setColor(Color.WHITE);
                g.setFont(uiFont);
                center(g, t("score") + " " + score + "   " + t("best") + " " + currentBest(), H / 2 - 12, uiFont);
                if (newRecord) {
                    g.setColor(new Color(255, 235, 90));
                    center(g, t("newrec"), H / 2, uiFont);
                }
                Rectangle rb = new Rectangle(40, H / 2 + 10, W - 80, 14);
                drawButton(g, rb, t("restart") + " / " + t("pressEsc"), rb.contains(mx, my), false);
            }
        }
    }

    private void drawEnemies(Graphics2D g) {
        for (Enemy en : enemies) {
            int x = (int) en.x, y = (int) en.y;
            boolean flash = en.hitFlash > 0;
            switch (en.type) {
                case T_GRUNT:
                    g.setColor(flash ? Color.WHITE : new Color(220, 55, 80));
                    g.fillRect(x, y, 9, 8);
                    g.setColor(new Color(255, 140, 140));
                    g.fillRect(x + 1, y + 1, 7, 2);
                    g.setColor(new Color(20, 8, 16));
                    g.fillRect(x + 2, y + 5, 2, 2);
                    g.fillRect(x + 5, y + 5, 2, 2);
                    break;
                case T_DARTER:
                    g.setColor(flash ? Color.WHITE : new Color(255, 210, 70));
                    g.fillRect(x + 2, y, 3, 6);
                    g.fillRect(x, y + 2, 7, 2);
                    g.setColor(new Color(140, 90, 10));
                    g.fillRect(x + 2, y + 3, 3, 2);
                    break;
                case T_TANK:
                    g.setColor(flash ? Color.WHITE : new Color(150, 70, 200));
                    g.fillRect(x, y, 13, 11);
                    g.setColor(new Color(200, 140, 255));
                    g.fillRect(x + 1, y + 1, 11, 3);
                    g.setColor(new Color(30, 12, 40));
                    g.fillRect(x + 2, y + 6, 3, 3);
                    g.fillRect(x + 8, y + 6, 3, 3);
                    break;
                case T_GUNNER:
                    g.setColor(flash ? Color.WHITE : new Color(60, 190, 210));
                    g.fillRect(x, y, 10, 7);
                    g.fillRect(x + 4, y + 7, 2, 2);
                    g.setColor(new Color(180, 240, 255));
                    g.fillRect(x + 1, y + 1, 8, 2);
                    break;
                case T_BOSS:
                    g.setColor(flash ? Color.WHITE : new Color(190, 30, 40));
                    g.fillRect(x, y + 2, 26, 8);
                    g.fillRect(x + 8, y, 10, 12);
                    g.setColor(new Color(255, 120, 60));
                    g.fillRect(x + 2, y + 3, 4, 4);
                    g.fillRect(x + 20, y + 3, 4, 4);
                    g.setColor(new Color(255, 235, 120));
                    g.fillRect(x + 11, y + 3, 4, 4);
                    g.setColor(new Color(60, 20, 20));
                    g.fillRect(20, H - 8, W - 40, 2);
                    g.setColor(new Color(255, 70, 70));
                    int hpW = (W - 40) * Math.max(en.hp, 0) / Math.max(1, bossHpMax());
                    g.fillRect(20, H - 8, hpW, 2);
                    break;
            }
        }
    }

    private void drawPowerUps(Graphics2D g) {
        for (PowerUp pu : powerups) {
            int x = (int) pu.x, y = (int) pu.y;
            Color c = PU_COLOR[pu.type];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
            g.fillRect(x, y, 8, 8);
            g.setColor(c);
            g.drawRect(x, y, 7, 7);
            g.fillRect(x + 1, y + 1, 1, 1);
            g.fillRect(x + 6, y + 6, 1, 1);
            g.setColor(Color.WHITE);
            g.setFont(uiBold);
            FontMetrics fm = g.getFontMetrics();
            char ch = PU_LETTER[pu.type];
            g.drawString(String.valueOf(ch), x + (8 - fm.charWidth(ch)) / 2, y + 7);
        }
    }

    private void drawPlayer(Graphics2D g) {
        if (state == State.OVER) return;
        if (invTimer > 0 && (tick / 4) % 2 == 0) return;
        g.setColor(new Color(60, 220, 130));
        g.fillRect(playerX + 3, PLAYER_Y - 3, 4, 4);
        g.fillRect(playerX, PLAYER_Y, PLAYER_W, 5);
        g.setColor(new Color(230, 255, 240));
        g.fillRect(playerX + 4, PLAYER_Y, 2, 3);
        if (weapon >= 2) { g.fillRect(playerX, PLAYER_Y - 2, 2, 2); g.fillRect(playerX + 8, PLAYER_Y - 2, 2, 2); }
        g.setColor(new Color(255, 150, 50));
        int flame = 1 + (tick / 4) % 2;
        g.fillRect(playerX + 3, PLAYER_Y + 5, 4, flame);
        if (shieldTimer > 0) {
            int a = shieldTimer < 120 ? ((shieldTimer / 6) % 2 == 0 ? 120 : 40) : 90;
            g.setColor(new Color(120, 255, 160, a));
            g.drawOval(playerX - 4, PLAYER_Y - 7, PLAYER_W + 8, PLAYER_H + 10);
        }
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(150, 170, 210));
        g.fillRect(0, H - 3, W, 1);
        g.setFont(uiFont);
        g.setColor(Color.WHITE);
        g.drawString(String.format("%s %04d", t("score"), score), 4, 10);
        g.drawString(String.format("%s %04d", t("best"), currentBest()), 4, 19);
        g.setColor(new Color(110, 130, 170));
        g.drawString("LV" + level + " WPN" + weapon + "  " + t("save") + (currentSave >= 0 && currentSave < saves.size() ? " " + saves.get(currentSave).id : ""), 4, 28);

        int mult = 1 + Math.min(combo / 5, 3);
        if (mult > 1) {
            g.setColor(new Color(255, 235, 90));
            g.drawString("COMBO x" + mult, 4, 37);
        }

        g.setColor(new Color(255, 80, 100));
        for (int i = 0; i < lives; i++) g.fillRect(W - 8 - i * 7, 4, 5, 5);

        int barY = 14;
        if (rapidTimer > 0) {
            g.setColor(new Color(90, 200, 255));
            g.fillRect(W - 24, barY, 16 * rapidTimer / 480, 2);
        }
        if (shieldTimer > 0) {
            g.setColor(new Color(120, 255, 160));
            g.fillRect(W - 24, barY + 4, 16 * shieldTimer / 600, 2);
        }
    }

    private void center(Graphics2D g, String s, int y, Font f) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (W - fm.stringWidth(s)) / 2, y);
    }

    // ---------- 输入 ----------

    @Override public void keyPressed(KeyEvent e) { pressed.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { pressed.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e) { }

    // ---------- 入口 ----------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Pixel Shooter " + VERSION);
            PixelShooter game = new PixelShooter();
            game.attachFrame(f);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    game.saveData();
                    game.closeLog("window closing (tick=" + game.tick + " state=" + game.state + ")");
                    e.getWindow().dispose();
                    System.exit(0);
                }
            });
            f.setResizable(false);
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.requestFocusInWindow();
        });
    }

    // ---------- 音效 ----------

    private enum Sound { SHOOT, HIT, LOSE, POWER, BOOM }

    private static final class Sfx {
        private static final AudioFormat FORMAT = new AudioFormat(44100f, 16, 1, true, false);
        private static final int POOL = 4;

        private final Clip[][] bank = new Clip[Sound.values().length][POOL];
        private final int[] cursor = new int[Sound.values().length];
        private boolean muted;

        Sfx() {
            for (Sound s : Sound.values()) {
                byte[] pcm = render(s);
                for (int i = 0; i < POOL; i++) {
                    try {
                        Clip c = AudioSystem.getClip();
                        c.open(new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT,
                                pcm.length / FORMAT.getFrameSize()));
                        bank[s.ordinal()][i] = c;
                    } catch (Exception ignored) { }
                }
            }
        }

        void play(Sound s) {
            if (muted) return;
            int k = s.ordinal();
            Clip c = bank[k][cursor[k]];
            cursor[k] = (cursor[k] + 1) % POOL;
            if (c == null) return;
            if (c.isRunning()) c.stop();
            c.setFramePosition(0);
            c.start();
        }

        boolean isMuted() { return muted; }

        void setMuted(boolean m) { muted = m; }

        void toggleMute() {
            muted = !muted;
            if (muted) {
                for (Clip[] arr : bank) {
                    for (Clip c : arr) if (c != null && c.isRunning()) c.stop();
                }
            }
        }

        private static byte[] render(Sound s) {
            switch (s) {
                case SHOOT: {
                    double dur = 0.10, f0 = 900, f1 = 190;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.50, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 3.0);
                        double buzz = Math.sin(t * 54321.0) * 0.12;
                        return (sq * 0.85 + buzz) * Math.pow(1 - t / dur, 2.0);
                    });
                }
                case HIT: {
                    double dur = 0.14, f0 = 460, f1 = 70;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.55, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 2.0);
                        double noise = Math.sin(t * 98765.0) + Math.sin(t * 31211.0);
                        return (sq * 0.55 + noise * 0.22) * Math.pow(1 - t / dur, 1.6);
                    });
                }
                case LOSE: {
                    double dur = 0.30, f0 = 210, f1 = 55;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.55, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 4.0);
                        return sq * Math.pow(1 - t / dur, 1.2);
                    });
                }
                case POWER: {
                    double dur = 0.22, f0 = 420, f1 = 1250;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.45, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 2.5);
                        return sq * Math.pow(1 - t / dur, 1.0);
                    });
                }
                case BOOM: {
                    double dur = 0.45, f0 = 160, f1 = 32;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.65, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 1.6);
                        double noise = Math.sin(t * 51234.0) + Math.sin(t * 17317.0) + Math.sin(t * 9257.0);
                        return (sq * 0.7 + noise * 0.18) * Math.pow(1 - t / dur, 1.4);
                    });
                }
            }
            return new byte[0];
        }

        private static byte[] synth(double seconds, double gain, DoubleUnaryOperator wave) {
            int n = (int) (FORMAT.getSampleRate() * seconds);
            byte[] out = new byte[n * FORMAT.getFrameSize()];
            for (int i = 0; i < n; i++) {
                double t = i / FORMAT.getSampleRate();
                double v = Math.max(-1.0, Math.min(1.0, wave.applyAsDouble(t) * gain));
                short sample = (short) (v * Short.MAX_VALUE);
                out[2 * i] = (byte) (sample & 0xFF);
                out[2 * i + 1] = (byte) ((sample >>> 8) & 0xFF);
            }
            return out;
        }
    }
}
