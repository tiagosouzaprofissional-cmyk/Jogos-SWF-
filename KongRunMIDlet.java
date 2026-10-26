import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.*;
import javax.microedition.rms.*;
import javax.microedition.media.*;
import java.util.Random;

public class KongRunMIDlet extends MIDlet {
    private Display display;
    private KongCanvas canvas;

    public void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            canvas = new KongCanvas(this);
            canvas.start();
        }
        display.setCurrent(canvas);
    }

    public void pauseApp() {
        if (canvas != null) canvas.pause();
    }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.stop();
        notifyDestroyed();
    }
}

class KongCanvas extends GameCanvas implements Runnable {
    private KongRunMIDlet midlet;
    private volatile boolean running = false;
    
    // Estados: 0 = MENU, 1 = JOGANDO, 2 = PAUSA, 3 = GAME OVER
    private int state = 0;
    private boolean soundOn = true;

    // Resolução nativa do Nokia X2-01 (QVGA Landscape 320x240)
    private static final int W = 320;
    private static final int H = 240;
    private static final int GROUND_Y = 198;
    private static final int SOFTBAR_H = 16;

    // Física do Gorila (Física multiplicada x10 para precisão sem ponto flutuante)
    private int kongX = 42;
    private int kongY = GROUND_Y * 10;
    private int kongVY = 0;
    private boolean jumping = false;
    private boolean sliding = false;
    private int slideTicks = 0;
    private int animFrame = 0;
    private int shakeTicks = 0;

    // Pool de Obstáculos (Máx 3 na tela para rodar liso na RAM do S40)
    private int[] obsX = new int[3];
    private int[] obsY = new int[3];
    private int[] obsW = new int[3];
    private int[] obsH = new int[3];
    private int[] obsType = new int[3]; // 0=Raiz, 1=Totem Alto, 2=Cipó Alto (Slide)
    private boolean[] obsPassed = new boolean[3];

    // Banana Coletável
    private int banX = -50, banY = 140;
    private boolean banActive = false;

    // Paralaxe de Selva
    private int[] bgPillars = {10, 100, 190, 280};
    private int[] bgTrees = {30, 120, 210, 300};

    // Pontuação e Combos
    private int score = 0;
    private int hiScore = 0;
    private int bananas = 0;
    private int combo = 1;
    private int comboTimer = 0;
    private int speed = 42; // 4.2 px por frame
    private int tick = 0;

    private Random rng = new Random();

    public KongCanvas(KongRunMIDlet m) {
        super(false);
        this.midlet = m;
        setFullScreenMode(true);
        loadHighScore();
        initObstacles();
    }

    private void initObstacles() {
        for (int i = 0; i < 3; i++) {
            obsX[i] = W + 80 + (i * 150);
            setupObstacle(i);
        }
        banX = W + 140;
        banY = GROUND_Y - 45;
        banActive = true;
    }

    private void setupObstacle(int i) {
        int r = Math.abs(rng.nextInt() % 100);
        obsPassed[i] = false;
        if (r < 40) {
            obsType[i] = 0; // Raiz baixa
            obsW[i] = 16;
            obsH[i] = 20;
            obsY[i] = GROUND_Y - 20;
        } else if (r < 72) {
            obsType[i] = 1; // Totem de pedra (pulo alto)
            obsW[i] = 18;
            obsH[i] = 28;
            obsY[i] = GROUND_Y - 28;
        } else {
            obsType[i] = 2; // Tronco/Cipó suspenso (rolar por baixo)
            obsW[i] = 24;
            obsH[i] = 24;
            obsY[i] = GROUND_Y - 38;
        }
    }

    public void start() {
        running = true;
        new Thread(this).start();
    }

    public void pause() {
        if (state == 1) state = 2;
    }

    public void stop() {
        running = false;
    }

    private void beep(int note, int duration) {
        if (!soundOn) return;
        try {
            Manager.playTone(note, duration, 80);
        } catch (Exception e) {}
    }

    private void loadHighScore() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("KongX2Hi", true);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                hiScore = Integer.parseInt(new String(data));
            }
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private void saveHighScore() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("KongX2Hi", true);
            byte[] bytes = String.valueOf(hiScore).getBytes();
            if (rs.getNumRecords() == 0) rs.addRecord(bytes, 0, bytes.length);
            else rs.setRecord(1, bytes, 0, bytes.length);
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private void resetGame() {
        score = 0;
        bananas = 0;
        combo = 1;
        comboTimer = 0;
        speed = 42;
        kongY = GROUND_Y * 10;
        kongVY = 0;
        jumping = false;
        sliding = false;
        initObstacles();
        state = 1;
        beep(72, 80);
    }

    protected void keyPressed(int keyCode) {
        // Softkeys do Nokia X2-01 (-6 = Esquerda, -7 = Direita)
        if (keyCode == -6) {
            if (state == 1) state = 2;
            else if (state == 2) state = 1;
            else if (state == 3) midlet.destroyApp(true);
            return;
        }
        if (keyCode == -7) {
            soundOn = !soundOn;
            if (soundOn) beep(76, 60);
            return;
        }

        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}

        if (state == 0 || state == 3) {
            resetGame();
            return;
        }
        if (state == 2) {
            state = 1;
            return;
        }

        // Pular: D-Pad Cima, Botão OK (FIRE), Tecla 2, Tecla 5, W ou Espaço (QWERTY do X2-01)
        if (action == UP || action == FIRE || keyCode == KEY_NUM2 || keyCode == KEY_NUM5 || keyCode == 'w' || keyCode == 'W' || keyCode == ' ') {
            if (!jumping) {
                jumping = true;
                sliding = false;
                kongVY = -74;
                beep(64, 70);
            }
        }
        // Rolar / Smash: D-Pad Baixo, Tecla 8 ou S (QWERTY do X2-01)
        else if (action == DOWN || keyCode == KEY_NUM8 || keyCode == 's' || keyCode == 'S') {
            if (jumping) {
                kongVY = 95; // Mergulho pesado no chão!
            } else if (!sliding) {
                sliding = true;
                slideTicks = 22;
                beep(55, 60);
            }
        }
    }

    public void run() {
        Graphics g = getGraphics();
        while (running) {
            long start = System.currentTimeMillis();
            if (state == 1) updateGame();
            render(g);
            flushGraphics();
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed < 33) {
                try { Thread.sleep(33 - elapsed); } catch (InterruptedException e) {}
            }
        }
    }

    private void updateGame() {
        tick++;
        int pixelSpeed = speed / 10;
        score += (pixelSpeed > 0 ? pixelSpeed : 1) * combo;
        if (score > hiScore) hiScore = score;

        if (tick % 90 == 0 && speed < 85) speed += 2;
        if (tick % 3 == 0) animFrame = (animFrame + 1) % 4;

        if (combo > 1) {
            comboTimer--;
            if (comboTimer <= 0) combo = 1;
        }

        // Física de Salto Pesado
        if (jumping) {
            kongY += kongVY;
            kongVY += 7; // Gravidade
            if (kongY >= GROUND_Y * 10) {
                int impact = kongVY;
                kongY = GROUND_Y * 10;
                kongVY = 0;
                jumping = false;
                if (impact > 80) {
                    shakeTicks = 5;
                    beep(45, 90);
                }
            }
        }

        if (sliding) {
            slideTicks--;
            if (slideTicks <= 0) sliding = false;
        }

        // Paralaxe
        if (tick % 2 == 0) {
            for (int i = 0; i < 4; i++) {
                bgPillars[i] -= 1;
                if (bgPillars[i] < -30) bgPillars[i] += 360;
            }
        }
        for (int i = 0; i < 4; i++) {
            bgTrees[i] -= (pixelSpeed / 2) + 1;
            if (bgTrees[i] < -30) bgTrees[i] += 360;
        }

        int kx = kongX + 4;
        int kw = 20;
        int kh = sliding ? 14 : 26;
        int ky = (kongY / 10) - kh;

        // Atualiza Obstáculos
        int maxRight = 0;
        for (int i = 0; i < 3; i++) {
            if (obsX[i] > maxRight) maxRight = obsX[i];
        }

        for (int i = 0; i < 3; i++) {
            obsX[i] -= pixelSpeed;

            // Colisão
            if (kx < obsX[i] + obsW[i] && kx + kw > obsX[i] &&
                ky < obsY[i] + obsH[i] && ky + kh > obsY[i]) {
                state = 3;
                shakeTicks = 8;
                beep(40, 300);
                saveHighScore();
                return;
            }

            if (!obsPassed[i] && obsX[i] + obsW[i] < kx) {
                obsPassed[i] = true;
                if (combo < 8) combo++;
                comboTimer = 80;
            }

            if (obsX[i] < -30) {
                int gap = 115 + Math.abs(rng.nextInt() % 70);
                obsX[i] = (maxRight > W ? maxRight : W) + gap;
                setupObstacle(i);
            }
        }

        // Banana
        banX -= pixelSpeed;
        if (banActive && kx < banX + 10 && kx + kw > banX && ky < banY + 10 && ky + kh > banY) {
            banActive = false;
            bananas++;
            score += 50 * combo;
            if (combo < 8) combo++;
            comboTimer = 95;
            beep(80, 70);
        }
        if (banX < -20) {
            banX = W + 60 + Math.abs(rng.nextInt() % 120);
            banY = GROUND_Y - (Math.abs(rng.nextInt() % 2) == 0 ? 14 : 46);
            banActive = true;
        }
    }

    private void drawKong(Graphics g, int x, int y) {
        // Sombra no chão
        g.setColor(0x060C06);
        g.fillRect(x + 2, GROUND_Y - 1, 24, 3);

        if (sliding) {
            int topY = y - 15;
            g.setColor(0x23262E);
            g.fillRect(x, topY + 2, 28, 13);
            g.setColor(0x7C8599); // Costas prateadas
            g.fillRect(x + 4, topY, 18, 5);
            g.setColor(0xFFCC00); // Olho
            g.fillRect(x + 25, topY + 5, 2, 2);
            return;
        }

        int bob = jumping ? 0 : (animFrame % 2 == 0 ? -2 : 0);
        int topY = y - 28 + bob;

        // Perna traseira
        g.setColor(0x16181D);
        int legOff = jumping ? -2 : (animFrame == 1 ? 3 : -2);
        g.fillRect(x + 3 + legOff, topY + 19, 6, 9);

        // Tronco e Costas Silverback
        g.setColor(0x252932);
        g.fillRect(x + 4, topY + 7, 18, 13);
        g.setColor(0x7C8599);
        g.fillRect(x + 5, topY + 6, 13, 6);

        // Cabeça e Focinho
        g.setColor(0x1B1E24);
        g.fillRect(x + 17, topY + 2, 10, 11);
        g.setColor(0x3D4352);
        g.fillRect(x + 23, topY + 7, 6, 6);
        g.setColor(0xFFCC00);
        g.fillRect(x + 23, topY + 4, 2, 2);

        // Braço pesado da frente
        int armOff = jumping ? -4 : (animFrame == 0 ? 4 : (animFrame == 2 ? -3 : 0));
        g.setColor(0x2F3440);
        g.fillRect(x + 15 + armOff, topY + 9, 7, 12);
        g.setColor(0x16181D);
        g.fillRect(x + 15 + armOff, topY + 19, 8, 9);
    }

    private void render(Graphics g) {
        int sx = 0, sy = 0;
        if (shakeTicks > 0) {
            shakeTicks--;
            sx = (rng.nextInt() % 5);
            sy = (rng.nextInt() % 5);
            g.translate(sx, sy);
        }

        // Céu da Selva em 3 faixas
        g.setColor(0x0D1B12);
        g.fillRect(0, 0, W, 60);
        g.setColor(0x14291B);
        g.fillRect(0, 60, W, 70);
        g.setColor(0x1D3824);
        g.fillRect(0, 130, W, GROUND_Y - 130);

        // Ruínas ao fundo
        g.setColor(0x112217);
        for (int i = 0; i < 4; i++) {
            g.fillRect(bgPillars[i], 65, 24, GROUND_Y - 65);
            g.fillRect(bgPillars[i] - 3, 60, 30, 6);
        }

        // Árvores da selva
        for (int i = 0; i < 4; i++) {
            g.setColor(0x09140D);
            g.fillRect(bgTrees[i], 24, 12, GROUND_Y - 24);
            g.setColor(0x18331E);
            g.fillRect(bgTrees[i] - 10, 22, 32, 16);
        }

        // Chão
        g.setColor(0x3B2614);
        g.fillRect(0, GROUND_Y, W, H - GROUND_Y - SOFTBAR_H);
        g.setColor(0x4CA632);
        g.fillRect(0, GROUND_Y, W, 4);

        // Obstáculos
        for (int i = 0; i < 3; i++) {
            if (obsType[i] == 0) {
                g.setColor(0x855327);
                g.fillRect(obsX[i], obsY[i], obsW[i], obsH[i]);
                g.setColor(0xD9C79E);
                g.fillRect(obsX[i] + 5, obsY[i] - 2, 4, 3);
            } else if (obsType[i] == 1) {
                g.setColor(0x5A6360);
                g.fillRect(obsX[i], obsY[i], obsW[i], obsH[i]);
                g.setColor(0xFF3B1F);
                g.fillRect(obsX[i] + 3, obsY[i] + 8, 3, 3);
                g.fillRect(obsX[i] + 11, obsY[i] + 8, 3, 3);
            } else {
                g.setColor(0x2C5E1E);
                g.fillRect(obsX[i] + 4, 0, obsW[i] - 8, obsY[i]);
                g.setColor(0x4E3116);
                g.fillRect(obsX[i], obsY[i], obsW[i], obsH[i]);
                g.setColor(0xA8E048);
                g.fillRect(obsX[i] + 2, obsY[i] + obsH[i] - 4, obsW[i] - 4, 4);
            }
        }

        // Banana Dourada
        if (banActive) {
            g.setColor(0xFFD700);
            g.fillRect(banX, banY, 7, 9);
            g.setColor(0xFFF59D);
            g.fillRect(banX + 2, banY + 2, 3, 5);
        }

        // Desenha o Gorila
        drawKong(g, kongX, kongY / 10);

        // HUD Superior
        g.setColor(0x08100A);
        g.fillRect(0, 0, W, 18);
        g.setColor(0xFFFFFF);
        g.drawString("PTS:" + score, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xFFD700);
        g.drawString("BAN:" + bananas + (combo > 1 ? " x" + combo : ""), 115, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xA8E048);
        g.drawString("HI:" + hiScore, 230, 2, Graphics.TOP | Graphics.LEFT);

        // Telas de Menu / Pause / Game Over
        if (state == 0) {
            g.setColor(0x0A140A);
            g.fillRect(36, 40, W - 72, 134);
            g.setColor(0xFFD700);
            g.drawRect(36, 40, W - 72, 134);
            g.drawString("KONG RUN - NOKIA X2-01", W / 2, 52, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xFFFFFF);
            g.drawString("CIMA / OK / 2 / W : Pular", W / 2, 82, Graphics.TOP | Graphics.HCENTER);
            g.drawString("BAIXO / 8 / S : Rolar", W / 2, 102, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xA8E048);
            g.drawString("Aperte OK ou 5 para Jogar!", W / 2, 142, Graphics.TOP | Graphics.HCENTER);
        } else if (state == 2) {
            g.setColor(0x000000);
            g.fillRect(90, 85, 140, 50);
            g.setColor(0xFFD700);
            g.drawRect(90, 85, 140, 50);
            g.drawString("PAUSADO", W / 2, 102, Graphics.TOP | Graphics.HCENTER);
        } else if (state == 3) {
            g.setColor(0x220505);
            g.fillRect(46, 48, W - 92, 120);
            g.setColor(0xFF3B1F);
            g.drawRect(46, 48, W - 92, 120);
            g.drawString("FIM DE JOGO!", W / 2, 60, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xFFFFFF);
            g.drawString("Pontos: " + score + " | Recorde: " + hiScore, W / 2, 92, Graphics.TOP | Graphics.HCENTER);
            g.setColor(0xFFD700);
            g.drawString("Aperte OK para Reiniciar", W / 2, 134, Graphics.TOP | Graphics.HCENTER);
        }

        if (sx != 0 || sy != 0) g.translate(-sx, -sy);

        // Barra de Softkeys do Nokia S40
        g.setColor(0x181C24);
        g.fillRect(0, H - SOFTBAR_H, W, SOFTBAR_H);
        g.setColor(0x4A5264);
        g.drawLine(0, H - SOFTBAR_H, W, H - SOFTBAR_H);
        g.setColor(0xFFFFFF);
        g.drawString(state == 1 ? "Pausa" : (state == 3 ? "Sair" : "Iniciar"), 4, H - 15, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xFFD700);
        g.drawString("OK", W / 2, H - 15, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0xFFFFFF);
        g.drawString(soundOn ? "Som:ON" : "Som:OFF", W - 4, H - 15, Graphics.TOP | Graphics.RIGHT);
    }
}
