import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * 像素射击游戏 v2（单文件、零依赖、JDK 8+ 即可运行）
 * 音效由 javax.sound.sampled 实时合成 PCM，不需要任何 wav/mp3 素材。
 *
 * 玩法：
 *   - 敌人有 4 种：小兵、疾锋、重装、炮手，以及周期性登场并释放弹幕的 BOSS
 *   - 击落敌人有概率掉落道具：W 武器升级 / R 急速射击 / S 护盾 / B 全屏炸弹 / + 生命
 *   - 连续击杀形成连击，分数倍率最高 x4；分数推进关卡，出怪更快更凶
 *   - 最高分自动存档
 *
 * 编译运行：
 *   javac -encoding UTF-8 PixelShooter.java
 *   java PixelShooter
 *
 * 操作：← / → 或 A / D 移动，空格发射，P 或 ESC 暂停，R 随时重新开始，M 静音
 */
public class PixelShooter extends JPanel implements ActionListener, KeyListener {

    // 低分辨率逻辑画布，放大后天然形成像素风
    static final int W = 200, H = 150, SCALE = 4;

    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private static final Random rnd = new Random();
    private final Set<Integer> pressed = new HashSet<>();
    private final Font font = new Font(Font.MONOSPACED, Font.PLAIN, 8);
    private final Sfx sfx = new Sfx();          // 初始化时合成并预加载全部音效

    private boolean muteHeld, restartHeld, pauseHeld;   // 按键边沿检测
    private enum State { START, PLAYING, PAUSED, OVER }
    private State state = State.START;

    // ---------- 实体 ----------
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
        final int type;            // 0=W 1=R 2=S 3=B 4=L
        PowerUp(double x, double y, int type) { this.x = x; this.y = y; this.type = type; }
    }
    private static final char[] PU_LETTER = {'W', 'R', 'S', 'B', '+'};
    private static final Color[] PU_COLOR = {
            new Color(255, 235, 90), new Color(90, 200, 255),
            new Color(120, 255, 160), new Color(255, 140, 60), new Color(255, 90, 130)};

    private final List<Bullet> shots = new ArrayList<>();       // 玩家子弹
    private final List<Bullet> eshots = new ArrayList<>();      // 敌方子弹
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<PowerUp> powerups = new ArrayList<>();
    private final int[][] stars = new int[48][3];               // {x, y, speed}

    private int playerX = W / 2 - 5;
    private static final int PLAYER_Y = H - 16, PLAYER_W = 10, PLAYER_H = 8;

    private int score, best, lives, level, cooldown, tick;
    private int weapon = 1;             // 1 单发 / 2 双发 / 3 三向
    private int rapidTimer, shieldTimer, invTimer, combo, comboTimer, bossWarn, bombFx, shake;
    private boolean newRecord;

    public PixelShooter() {
        setPreferredSize(new Dimension(W * SCALE, H * SCALE));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        for (int i = 0; i < stars.length; i++) {
            stars[i] = new int[]{rnd.nextInt(W), rnd.nextInt(H), 1 + rnd.nextInt(2)};
        }
        best = loadBest();
        reset();
        new javax.swing.Timer(1000 / 60, this).start();   // 固定 60 FPS 逻辑帧
    }

    private void reset() {
        shots.clear(); eshots.clear(); enemies.clear(); particles.clear(); powerups.clear();
        score = 0; lives = 3; level = 1; cooldown = 0; tick = 0;
        weapon = 1; rapidTimer = 0; shieldTimer = 0; invTimer = 0;
        combo = 0; comboTimer = 0; bossWarn = 0; bombFx = 0; shake = 0; newRecord = false;
        playerX = W / 2 - 5;
    }

    // ---------- 最高分持久化 ----------

    private static Path bestFile() {
        return Paths.get(System.getProperty("user.home"), ".pixelshooter_hi.txt");
    }

    private int loadBest() {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(bestFile()), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveBest() {
        try {
            Files.write(bestFile(), Integer.toString(best).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    // ---------- 逻辑 ----------

    @Override
    public void actionPerformed(ActionEvent e) {
        tick++;
        boolean left = pressed.contains(KeyEvent.VK_LEFT) || pressed.contains(KeyEvent.VK_A);
        boolean right = pressed.contains(KeyEvent.VK_RIGHT) || pressed.contains(KeyEvent.VK_D);
        boolean fire = pressed.contains(KeyEvent.VK_SPACE);

        boolean m = pressed.contains(KeyEvent.VK_M);
        if (m && !muteHeld) sfx.toggleMute();
        muteHeld = m;

        boolean p = pressed.contains(KeyEvent.VK_P) || pressed.contains(KeyEvent.VK_ESCAPE);
        if (p && !pauseHeld && (state == State.PLAYING || state == State.PAUSED)) {
            state = state == State.PLAYING ? State.PAUSED : State.PLAYING;
        }
        pauseHeld = p;

        // R：游戏内随时重新开始（对局中、暂停中、结束后均可）
        boolean r = pressed.contains(KeyEvent.VK_R);
        if (r && !restartHeld && state != State.START) {
            reset();
            state = State.PLAYING;
        }
        restartHeld = r;

        if (state == State.START && fire) state = State.PLAYING;

        if (state == State.PLAYING) updateGame(left, right, fire);

        // 粒子与星空在任何状态下都继续动画
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

    private void updateGame(boolean left, boolean right, boolean fire) {
        // 计时器
        if (rapidTimer > 0) rapidTimer--;
        if (shieldTimer > 0) shieldTimer--;
        if (invTimer > 0) invTimer--;
        if (bossWarn > 0) bossWarn--;
        if (comboTimer > 0 && --comboTimer == 0) combo = 0;

        level = 1 + score / 120;

        // 玩家移动
        if (left) playerX -= 2;
        if (right) playerX += 2;
        playerX = Math.max(2, Math.min(W - PLAYER_W - 2, playerX));

        // 射击
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

        // 出怪：关卡越高越快；每 30 秒刷一只 BOSS
        boolean bossAlive = false;
        for (Enemy en : enemies) if (en.type == T_BOSS) { bossAlive = true; break; }
        if (!bossAlive && tick > 600 && tick % 1800 == 0) {
            enemies.add(new Enemy(T_BOSS, W / 2 - EW[T_BOSS] / 2, 0.5, bossHpMax()));
            bossWarn = 90;
            sfx.play(Sound.BOOM);
        }
        if (!bossAlive || tick % 240 == 0) {   // BOSS 在场时杂兵减半
            int interval = Math.max(16, 70 - level * 6);
            if (tick % interval == 0) spawnEnemy();
        }

        // 敌人行为
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

            // 玩家子弹命中
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

            // 撞到玩家
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
            // 漏过底线
            if (en.y > H) {
                enemies.remove(i);
                if (en.type != T_BOSS) {
                    burst(en.x + EW[en.type] / 2, H - 2, new Color(255, 200, 60), 8, 1.8);
                    damagePlayer(en.x, H - 10, new Color(255, 200, 60));
                }
            }
        }

        // 敌方子弹命中玩家
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

        // 道具下落与拾取
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

    private int bossHpMax() {
        return Math.min(14 + level * 4, 40);
    }

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
        // 掉落：BOSS 必掉两个，小怪 12%
        int drops = en.type == T_BOSS ? 2 : (rnd.nextDouble() < 0.12 ? 1 : 0);
        for (int d = 0; d < drops; d++) {
            powerups.add(new PowerUp(en.x + rnd.nextInt(6), en.y, rollPowerUp()));
        }
    }

    private int rollPowerUp() {
        int roll = rnd.nextInt(100);
        if (lives < 5 && roll < 10) return 4;              // +1 命
        if (roll < 25) return 3;                            // 炸弹
        if (roll < 45) return 2;                            // 护盾
        if (roll < 70) return 1;                            // 急速
        return 0;                                           // 武器
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

    /** 全屏炸弹：清空敌机与敌方子弹 */
    private void detonate() {
        bombFx = 10; shake = 10;
        sfx.play(Sound.BOOM);
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy en = enemies.get(i);
            if (en.type == T_BOSS) {   // BOSS 只掉 6 血不清场
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
            if (score > best) { best = score; newRecord = true; saveBest(); }
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
        g.setFont(font);

        g.setColor(new Color(10, 12, 24));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(45, 55, 90));
        for (int[] s : stars) g.fillRect(s[0], s[1], 1, 1);

        // 屏幕震动
        if (shake > 0) g.translate(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);

        drawEnemies(g);
        drawPowerUps(g);

        // 子弹
        g.setColor(new Color(255, 235, 90));
        for (Bullet b : shots) g.fillRect((int) b.x, (int) b.y, 2, 4);
        g.setColor(new Color(255, 100, 220));
        for (Bullet b : eshots) g.fillRect((int) b.x, (int) b.y, 2, 3);

        drawPlayer(g);

        // 粒子
        for (Particle p : particles) {
            g.setColor(p.life > 8 ? p.c : new Color(120, 60, 40));
            g.fillRect((int) p.x, (int) p.y, 2, 2);
        }

        // BOSS 警告
        if (bossWarn > 0 && (bossWarn / 10) % 2 == 0) {
            g.setColor(new Color(255, 70, 70));
            center(g, "! BOSS INCOMING !", H / 2 - 26, 9);
        }

        drawHud(g);

        // 炸弹白闪（覆盖在菜单遮罩之前绘制，避免遮挡文字）
        if (bombFx > 0) {
            g.setColor(new Color(255, 255, 255, bombFx * 18));
            g.fillRect(-4, -4, W + 8, H + 8);
        }

        if (state == State.START || state == State.OVER || state == State.PAUSED) {
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(-4, -4, W + 8, H + 8);
            if (state == State.PAUSED) {
                g.setColor(new Color(120, 200, 255));
                center(g, "PAUSED", H / 2 - 10, 10);
                g.setColor(Color.WHITE);
                center(g, "P RESUME   R RESTART", H / 2 + 10, 8);
            } else {
                g.setColor(state == State.OVER ? new Color(255, 90, 90) : new Color(90, 230, 160));
                center(g, state == State.OVER ? "GAME OVER" : "PIXEL SHOOTER", H / 2 - 26, 10);
                g.setColor(Color.WHITE);
                if (state == State.OVER) {
                    center(g, "SCORE " + score + "   BEST " + best, H / 2 - 10, 8);
                    if (newRecord) {
                        g.setColor(new Color(255, 235, 90));
                        center(g, "* NEW RECORD *", H / 2 + 2, 8);
                    }
                    center(g, "PRESS R TO RESTART", H / 2 + 16, 8);
                } else {
                    center(g, "BEST " + best, H / 2 - 12, 8);
                    center(g, "PRESS SPACE TO START", H / 2 + 2, 8);
                }
                g.setColor(new Color(160, 175, 205));
                center(g, "AD/<> MOVE  SPACE FIRE  P PAUSE", H / 2 + 32, 7);
                center(g, "R RESTART   M MUTE", H / 2 + 42, 7);
            }
        }

        g.dispose();
        Graphics2D g2 = (Graphics2D) gr;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(canvas, 0, 0, getWidth(), getHeight(), null);
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
                    // BOSS 血条
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
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 6));
            FontMetrics fm = g.getFontMetrics();
            char ch = PU_LETTER[pu.type];
            g.drawString(String.valueOf(ch), x + (8 - fm.charWidth(ch)) / 2, y + 6);
        }
        g.setFont(font);
    }

    private void drawPlayer(Graphics2D g) {
        if (state == State.OVER) return;
        if (invTimer > 0 && (tick / 4) % 2 == 0) return;   // 受击闪烁
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
        g.setColor(Color.WHITE);
        g.drawString(String.format("SCORE %04d", score), 4, 10);
        g.drawString(String.format("BEST %04d", best), 4, 19);
        g.setColor(new Color(110, 130, 170));
        g.drawString("LV" + level + " WPN" + weapon, 4, 28);
        g.drawString(sfx.isMuted() ? "SFX OFF (M)" : "SFX ON  (M)", 4, 37);

        int mult = 1 + Math.min(combo / 5, 3);
        if (mult > 1) {
            g.setColor(new Color(255, 235, 90));
            g.drawString("COMBO x" + mult, 4, 46);
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

    private void center(Graphics2D g, String s, int y, int size) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size));
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
            JFrame f = new JFrame("Pixel Shooter");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            PixelShooter game = new PixelShooter();
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.requestFocusInWindow();
        });
    }

    // ---------- 音效：运行时合成 PCM，无需任何 wav/mp3 素材 ----------

    private enum Sound { SHOOT, HIT, LOSE, POWER, BOOM }

    private static final class Sfx {
        // 44.1kHz / 16-bit / 单声道 / 有符号小端
        private static final AudioFormat FORMAT =
                new AudioFormat(44100f, 16, 1, true, false);
        private static final int POOL = 4;   // 每种音效多个 Clip 轮换，连发不互相打断

        private final Clip[][] bank = new Clip[Sound.values().length][POOL];
        private final int[] cursor = new int[Sound.values().length];
        private boolean muted;

        Sfx() {
            for (Sound s : Sound.values()) {
                byte[] pcm = render(s);
                for (int i = 0; i < POOL; i++) {
                    try {
                        Clip c = AudioSystem.getClip();
                        c.open(new AudioInputStream(
                                new ByteArrayInputStream(pcm), FORMAT,
                                pcm.length / FORMAT.getFrameSize()));
                        bank[s.ordinal()][i] = c;
                    } catch (Exception ignored) {
                        // 无音频设备（如无头环境）时静默降级，游戏逻辑照常运行
                        bank[s.ordinal()][i] = null;
                    }
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
            c.start();                       // 非阻塞，不占用 Swing 事件线程
        }

        boolean isMuted() { return muted; }

        void toggleMute() {
            muted = !muted;
            if (muted) {
                for (Clip[] arr : bank) {
                    for (Clip c : arr) if (c != null && c.isRunning()) c.stop();
                }
            }
        }

        /** 线性扫频 + 软化方波 + 衰减包络，模拟复古芯片音 */
        private static byte[] render(Sound s) {
            switch (s) {
                case SHOOT: {                     // 短促下滑的「啾」
                    double dur = 0.10, f0 = 900, f1 = 190;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.50, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 3.0);
                        double buzz = Math.sin(t * 54321.0) * 0.12;
                        return (sq * 0.85 + buzz) * Math.pow(1 - t / dur, 2.0);
                    });
                }
                case HIT: {                       // 带噪声的爆炸
                    double dur = 0.14, f0 = 460, f1 = 70;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.55, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 2.0);
                        double noise = Math.sin(t * 98765.0) + Math.sin(t * 31211.0);
                        return (sq * 0.55 + noise * 0.22) * Math.pow(1 - t / dur, 1.6);
                    });
                }
                case LOSE: {                      // 低沉的失误音
                    double dur = 0.30, f0 = 210, f1 = 55;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.55, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 4.0);
                        return sq * Math.pow(1 - t / dur, 1.2);
                    });
                }
                case POWER: {                     // 上行的拾取琶音
                    double dur = 0.22, f0 = 420, f1 = 1250;
                    double k = (f1 - f0) / dur;
                    return synth(dur, 0.45, t -> {
                        double ph = 2 * Math.PI * (f0 * t + 0.5 * k * t * t);
                        double sq = Math.tanh(Math.sin(ph) * 2.5);
                        return sq * Math.pow(1 - t / dur, 1.0);
                    });
                }
                case BOOM: {                      // 厚重的炸弹轰鸣
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
                out[2 * i] = (byte) (sample & 0xFF);          // 低字节
                out[2 * i + 1] = (byte) ((sample >>> 8) & 0xFF); // 高字节
            }
            return out;
        }
    }
}
