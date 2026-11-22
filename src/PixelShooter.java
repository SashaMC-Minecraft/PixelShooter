import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * 极简像素射击游戏（单文件、零依赖、JDK 8+ 即可运行）
 * 音效由 javax.sound.sampled 实时合成 PCM，不需要任何 wav/mp3 素材。
 *
 * 编译运行：
 *   javac -encoding UTF-8 PixelShooter.java
 *   java PixelShooter
 *
 * 操作：← / → 或 A / D 移动，空格发射，R 重新开始，M 静音开关
 */
public class PixelShooter extends JPanel implements ActionListener, KeyListener {

    // 低分辨率逻辑画布，放大后天然形成像素风
    static final int W = 200, H = 150, SCALE = 4;

    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private final Random rnd = new Random();
    private final Set<Integer> pressed = new HashSet<>();
    private final Font font = new Font(Font.MONOSPACED, Font.PLAIN, 8);
    private final Sfx sfx = new Sfx();          // 初始化时合成并预加载全部音效

    private boolean muteHeld, restartHeld;      // 按键边沿检测，避免按住时反复触发
    private enum State { START, PLAYING, OVER }
    private State state = State.START;

    private final List<int[]> bullets = new ArrayList<>();   // {x, y}
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final int[][] stars = new int[40][3];            // {x, y, speed}

    private int playerX = W / 2 - 5;
    private static final int PLAYER_Y = H - 16, PLAYER_W = 10, PLAYER_H = 8;

    private int score, lives, cooldown, tick;

    public PixelShooter() {
        setPreferredSize(new Dimension(W * SCALE, H * SCALE));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        for (int i = 0; i < stars.length; i++) {
            stars[i] = new int[]{rnd.nextInt(W), rnd.nextInt(H), 1 + rnd.nextInt(2)};
        }
        reset();
        new javax.swing.Timer(1000 / 60, this).start();   // 固定 60 FPS 逻辑帧
    }

    private void reset() {
        bullets.clear();
        enemies.clear();
        particles.clear();
        score = 0;
        lives = 3;
        cooldown = 0;
        tick = 0;
        playerX = W / 2 - 5;
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

        boolean r = pressed.contains(KeyEvent.VK_R);
        if (state == State.OVER && r && !restartHeld) {
            reset();
            state = State.PLAYING;
        }
        restartHeld = r;

        if (state == State.START && fire) state = State.PLAYING;

        if (state == State.PLAYING) {
            if (left) playerX -= 2;
            if (right) playerX += 2;
            playerX = Math.max(2, Math.min(W - PLAYER_W - 2, playerX));

            if (fire && cooldown <= 0) {
                bullets.add(new int[]{playerX + PLAYER_W / 2 - 1, PLAYER_Y - 4});
                cooldown = 9;                       // 射速冷却
                sfx.play(Sound.SHOOT);
            }
            if (cooldown > 0) cooldown--;

            for (int i = bullets.size() - 1; i >= 0; i--) {
                int[] b = bullets.get(i);
                b[1] -= 3;
                if (b[1] < -6) bullets.remove(i);
            }

            int interval = Math.max(20, 75 - score / 8);   // 分数越高，出怪越快
            if (tick % interval == 0) {
                enemies.add(new Enemy(6 + rnd.nextInt(W - 20),
                        0.35 + rnd.nextDouble() * 0.35 + score * 0.0015,
                        rnd.nextInt(100)));
            }

            for (int i = enemies.size() - 1; i >= 0; i--) {
                Enemy en = enemies.get(i);
                en.y += en.speed;
                en.x += Math.sin((tick + en.seed) * 0.06) * 0.6;   // 轻微摆动
                en.x = Math.max(2, Math.min(W - 11, en.x));

                boolean gone = false;
                for (int j = bullets.size() - 1; j >= 0; j--) {
                    int[] b = bullets.get(j);
                    if (hit(b[0], b[1], 2, 4, (int) en.x, (int) en.y, 9, 8)) {
                        bullets.remove(j);
                        burst(en.x + 4, en.y + 4, new Color(255, 90, 90));
                        sfx.play(Sound.HIT);
                        score += 10;
                        gone = true;
                        break;
                    }
                }
                if (gone) { enemies.remove(i); continue; }

                if (hit(playerX, PLAYER_Y, PLAYER_W, PLAYER_H, (int) en.x, (int) en.y, 9, 8)
                        || en.y > H) {
                    burst(en.x + 4, Math.min(en.y + 4, H - 2), new Color(255, 200, 60));
                    enemies.remove(i);
                    sfx.play(Sound.LOSE);
                    if (--lives <= 0) { lives = 0; state = State.OVER; }
                }
            }
        }

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.x += p.vx; p.y += p.vy; p.vy += 0.04;
            if (--p.life <= 0) particles.remove(i);
        }
        for (int[] s : stars) {
            s[1] += state == State.PLAYING ? s[2] : 1;
            if (s[1] >= H) { s[1] = 0; s[0] = rnd.nextInt(W); }
        }
        repaint();
    }

    private static boolean hit(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    private void burst(double x, double y, Color c) {
        for (int i = 0; i < 10; i++) {
            particles.add(new Particle(x, y,
                    (rnd.nextDouble() - 0.5) * 2.2,
                    (rnd.nextDouble() - 0.5) * 2.2,
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

        // 敌人
        for (Enemy en : enemies) {
            int x = (int) en.x, y = (int) en.y;
            g.setColor(new Color(220, 55, 80));
            g.fillRect(x, y, 9, 8);
            g.setColor(new Color(255, 140, 140));
            g.fillRect(x + 1, y + 1, 7, 2);
            g.setColor(new Color(20, 8, 16));
            g.fillRect(x + 2, y + 5, 2, 2);
            g.fillRect(x + 5, y + 5, 2, 2);
        }

        // 子弹
        g.setColor(new Color(255, 235, 90));
        for (int[] b : bullets) g.fillRect(b[0], b[1], 2, 4);

        // 玩家小飞船
        if (state != State.OVER) {
            g.setColor(new Color(60, 220, 130));
            g.fillRect(playerX + 3, PLAYER_Y - 3, 4, 4);
            g.fillRect(playerX, PLAYER_Y, PLAYER_W, 5);
            g.setColor(new Color(230, 255, 240));
            g.fillRect(playerX + 4, PLAYER_Y, 2, 3);
            g.setColor(new Color(255, 150, 50));
            int flame = 1 + (tick / 4) % 2;
            g.fillRect(playerX + 3, PLAYER_Y + 5, 4, flame);
        }

        // 粒子
        for (Particle p : particles) {
            g.setColor(p.life > 8 ? p.c : new Color(120, 60, 40));
            g.fillRect((int) p.x, (int) p.y, 2, 2);
        }

        // HUD
        g.setColor(new Color(150, 170, 210));
        g.fillRect(0, H - 3, W, 1);
        g.setColor(Color.WHITE);
        g.drawString(String.format("SCORE %04d", score), 4, 10);
        g.setColor(new Color(110, 130, 170));
        g.drawString(sfx.isMuted() ? "SFX OFF (M)" : "SFX ON  (M)", 4, 20);
        g.setColor(new Color(255, 80, 100));
        for (int i = 0; i < lives; i++) g.fillRect(W - 8 - i * 7, 4, 5, 5);

        if (state == State.START || state == State.OVER) {
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(0, 0, W, H);
            g.setColor(state == State.OVER ? new Color(255, 90, 90) : new Color(90, 230, 160));
            center(g, state == State.OVER ? "GAME OVER" : "PIXEL SHOOTER", H / 2 - 16, 10);
            g.setColor(Color.WHITE);
            if (state == State.OVER) center(g, "SCORE " + score, H / 2, 8);
            center(g, state == State.OVER ? "PRESS R TO RESTART" : "PRESS SPACE TO START", H / 2 + 14, 8);
            g.setColor(new Color(160, 175, 205));
            center(g, "<>/AD MOVE  SPACE FIRE  M MUTE", H / 2 + 30, 7);
        }

        g.dispose();
        Graphics2D g2 = (Graphics2D) gr;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(canvas, 0, 0, getWidth(), getHeight(), null);
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

    private static class Enemy {
        double x, y = -10, speed;
        final int seed;
        Enemy(double x, double speed, int seed) { this.x = x; this.speed = speed; this.seed = seed; }
    }

    private static class Particle {
        double x, y, vx, vy;
        int life;
        final Color c;
        Particle(double x, double y, double vx, double vy, int life, Color c) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.life = life; this.c = c;
        }
    }

    // ---------- 音效：运行时合成 PCM，无需任何 wav/mp3 素材 ----------

    private enum Sound { SHOOT, HIT, LOSE }

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
