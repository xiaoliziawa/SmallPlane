package com.lirxowo.smallplane.earlydisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.earlydisplay.render.LoadingScreenRenderer;
import net.neoforged.fml.earlydisplay.render.RenderContext;
import net.neoforged.fml.earlydisplay.render.SimpleFont;
import net.neoforged.fml.earlydisplay.theme.Theme;
import net.neoforged.fml.earlydisplay.theme.ThemeColor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

final class PlaneGame {
    private static final String SPRITE_RESOURCE = "/smallplane/sprites/plane.spr";

    private static final long DOUBLE_TAP_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(350);
    private static final long SHOT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long DEBRIS_LIFETIME_NANOS = TimeUnit.MILLISECONDS.toNanos(900);
    private static final float MAX_STEP_SECONDS = 0.1f;
    private static final float NANOS_PER_SECOND = 1_000_000_000f;

    private static final float PIXEL_SCALE = 3f;
    private static final float PLANE_TOP = 408f;
    private static final float PLANE_SPEED = 330f;

    private static final int MAX_BULLETS = 32;
    private static final float BULLET_WIDTH = 3f;
    private static final float BULLET_HEIGHT = 10f;
    private static final float BULLET_SPEED = 640f;
    private static final int BULLET_COLOR = 0xFFFFE066;

    private static final int MAX_DEBRIS = 240;
    private static final int DEBRIS_PER_HIT = 4;
    private static final float DEBRIS_SIZE = 4f;
    private static final float DEBRIS_SPEED = 150f;
    private static final float DEBRIS_RISE = 90f;
    private static final float DEBRIS_GRAVITY = 460f;
    private static final int DEBRIS_RGB = 0xFFFFFF;
    private static final int ALPHA_SHIFT = 24;
    private static final int MAX_ALPHA = 255;

    private static final float PROMPT_TOP = 362f;
    private static final int RAINBOW_STEPS = 120;
    private static final int RAINBOW_STEPS_PER_CHARACTER = 4;
    private static final long RAINBOW_CYCLE_NANOS = TimeUnit.MILLISECONDS.toNanos(2200);
    private static final float RAINBOW_SATURATION = 0.85f;
    private static final float RAINBOW_BRIGHTNESS = 1f;
    private static final int[] RAINBOW = createRainbow();

    private static final long BOB_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(900);
    private static final float BOB_AMPLITUDE = 3f;
    private static final float BOB_PHASE_PER_CHARACTER = 0.6f;
    private static final double FULL_TURN = Math.PI * 2;

    private final EarlyText text;
    private final @Nullable PixelSprite plane;
    private final Queue<int[]> pendingKeys = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();

    private final float[] bulletX = new float[MAX_BULLETS];
    private final float[] bulletY = new float[MAX_BULLETS];
    private int bulletCount;

    private final float[] debrisX = new float[MAX_DEBRIS];
    private final float[] debrisY = new float[MAX_DEBRIS];
    private final float[] debrisVelocityX = new float[MAX_DEBRIS];
    private final float[] debrisVelocityY = new float[MAX_DEBRIS];
    private final long[] debrisDeadline = new long[MAX_DEBRIS];
    private int debrisCount;

    private volatile boolean summoned;
    private volatile boolean holdingHandoff;
    private volatile boolean handoffReleased;

    private float planeCenterX;
    private boolean movingLeft;
    private boolean movingRight;
    private long lastSpacePressNanos;
    private long lastShotNanos;
    private long lastUpdateNanos;
    private final List<SimpleFont.DisplayText> promptCharacter = new ArrayList<>(1);
    private String @Nullable [] promptCharacters;
    private float[] promptOffsets = new float[0];
    private float promptLeft;

    PlaneGame(EarlyText text) {
        this.text = text;
        this.plane = PixelSprite.load(SPRITE_RESOURCE);
        long now = System.nanoTime();
        this.lastSpacePressNanos = now - DOUBLE_TAP_WINDOW_NANOS;
        this.lastShotNanos = now - SHOT_INTERVAL_NANOS;
        this.lastUpdateNanos = now;
    }

    void onKey(int key, int action) {
        if (plane != null && action != GLFW.GLFW_REPEAT) {
            pendingKeys.add(new int[] { key, action });
        }
    }

    boolean isSummoned() {
        return summoned;
    }

    void holdHandoff() {
        holdingHandoff = true;
    }

    boolean isHandoffReleased() {
        return handoffReleased;
    }

    void update(long nowNanos, @Nullable LogoTarget logo) {
        handleKeys(nowNanos);

        float seconds = Math.min((nowNanos - lastUpdateNanos) / NANOS_PER_SECOND, MAX_STEP_SECONDS);
        lastUpdateNanos = nowNanos;
        if (!summoned || plane == null) {
            return;
        }

        float halfWidth = plane.width() * PIXEL_SCALE / 2f;
        int direction = (movingRight ? 1 : 0) - (movingLeft ? 1 : 0);
        planeCenterX = Math.clamp(
                planeCenterX + direction * PLANE_SPEED * seconds, halfWidth, LoadingScreenRenderer.LAYOUT_WIDTH - halfWidth);

        moveBullets(seconds, logo, nowNanos);
        moveDebris(seconds, nowNanos);
    }

    void render(RenderContext context, QuadBatch batch, SimpleFont font) {
        if (!summoned || plane == null) {
            return;
        }

        context.bindShader(Theme.SHADER_COLOR);
        batch.begin();
        plane.draw(batch, planeCenterX - plane.width() * PIXEL_SCALE / 2f, PLANE_TOP, PIXEL_SCALE);
        for (int i = 0; i < bulletCount; i++) {
            batch.colored(bulletX[i], bulletY[i], bulletX[i] + BULLET_WIDTH, bulletY[i] + BULLET_HEIGHT, BULLET_COLOR);
        }
        for (int i = 0; i < debrisCount; i++) {
            int alpha = (int) (MAX_ALPHA * Math.clamp((debrisDeadline[i] - lastUpdateNanos) / (float) DEBRIS_LIFETIME_NANOS, 0f, 1f));
            batch.colored(
                    debrisX[i], debrisY[i], debrisX[i] + DEBRIS_SIZE, debrisY[i] + DEBRIS_SIZE, alpha << ALPHA_SHIFT | DEBRIS_RGB);
        }
        batch.draw();

        if (holdingHandoff && !handoffReleased) {
            renderPrompt(context, font);
        }
    }

    private void renderPrompt(RenderContext context, SimpleFont font) {
        if (promptCharacters == null) {
            layOutPrompt(font);
        }

        int hueStep = (int) (lastUpdateNanos / (RAINBOW_CYCLE_NANOS / RAINBOW_STEPS));
        double wavePhase = lastUpdateNanos % BOB_PERIOD_NANOS / (double) BOB_PERIOD_NANOS * FULL_TURN;
        for (int i = 0; i < promptCharacters.length; i++) {
            int color = RAINBOW[Math.floorMod(hueStep - i * RAINBOW_STEPS_PER_CHARACTER, RAINBOW_STEPS)];
            float top = PROMPT_TOP + (float) Math.sin(wavePhase + i * BOB_PHASE_PER_CHARACTER) * BOB_AMPLITUDE;
            promptCharacter.clear();
            promptCharacter.add(new SimpleFont.DisplayText(promptCharacters[i], color));
            context.renderTextWithShadow(promptLeft + promptOffsets[i], top, font, promptCharacter);
        }
    }

    private void layOutPrompt(SimpleFont font) {
        String prompt = text.get(EarlyText.KEY_PRESS_ENTER);
        promptCharacters = new String[prompt.length()];
        promptOffsets = new float[prompt.length()];
        float width = 0f;
        for (int i = 0; i < prompt.length(); i++) {
            promptCharacters[i] = String.valueOf(prompt.charAt(i));
            promptOffsets[i] = width;
            width += font.stringWidth(promptCharacters[i]);
        }
        promptLeft = (LoadingScreenRenderer.LAYOUT_WIDTH - width) / 2f;
    }

    private static int[] createRainbow() {
        int[] colors = new int[RAINBOW_STEPS];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = ThemeColor.ofHsb(i / (float) RAINBOW_STEPS, RAINBOW_SATURATION, RAINBOW_BRIGHTNESS).toArgb();
        }
        return colors;
    }

    private void handleKeys(long nowNanos) {
        for (int[] event = pendingKeys.poll(); event != null; event = pendingKeys.poll()) {
            boolean pressed = event[1] == GLFW.GLFW_PRESS;
            switch (event[0]) {
                case GLFW.GLFW_KEY_SPACE -> {
                    if (pressed) {
                        pressSpace(nowNanos);
                    }
                }
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> movingLeft = pressed;
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> movingRight = pressed;
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (pressed && holdingHandoff) {
                        handoffReleased = true;
                    }
                }
                default -> {

                }
            }
        }
    }

    private void pressSpace(long nowNanos) {
        if (!summoned) {
            if (nowNanos - lastSpacePressNanos <= DOUBLE_TAP_WINDOW_NANOS) {
                summoned = true;
                planeCenterX = LoadingScreenRenderer.LAYOUT_WIDTH / 2f;
            }
            lastSpacePressNanos = nowNanos;
        } else if (nowNanos - lastShotNanos >= SHOT_INTERVAL_NANOS && bulletCount < MAX_BULLETS) {
            lastShotNanos = nowNanos;
            bulletX[bulletCount] = planeCenterX - BULLET_WIDTH / 2f;
            bulletY[bulletCount] = PLANE_TOP - BULLET_HEIGHT;
            bulletCount++;
        }
    }

    private void moveBullets(float seconds, @Nullable LogoTarget logo, long nowNanos) {
        for (int i = bulletCount - 1; i >= 0; i--) {
            float previousY = bulletY[i];
            bulletY[i] = previousY - BULLET_SPEED * seconds;
            int cell = logo == null ? -1 : logo.shoot(bulletX[i] + BULLET_WIDTH / 2f, previousY, bulletY[i]);
            if (cell >= 0) {
                spawnDebris(logo.cellCenterX(cell), logo.cellCenterY(cell), nowNanos);
            } else if (bulletY[i] + BULLET_HEIGHT > 0f) {
                continue;
            }
            bulletCount--;
            bulletX[i] = bulletX[bulletCount];
            bulletY[i] = bulletY[bulletCount];
        }
    }

    private void spawnDebris(float x, float y, long nowNanos) {
        for (int i = 0; i < DEBRIS_PER_HIT && debrisCount < MAX_DEBRIS; i++) {
            debrisX[debrisCount] = x;
            debrisY[debrisCount] = y;
            debrisVelocityX[debrisCount] = (random.nextFloat() * 2f - 1f) * DEBRIS_SPEED;
            debrisVelocityY[debrisCount] = -random.nextFloat() * DEBRIS_RISE;
            debrisDeadline[debrisCount] = nowNanos + DEBRIS_LIFETIME_NANOS;
            debrisCount++;
        }
    }

    private void moveDebris(float seconds, long nowNanos) {
        for (int i = debrisCount - 1; i >= 0; i--) {
            debrisVelocityY[i] += DEBRIS_GRAVITY * seconds;
            debrisX[i] += debrisVelocityX[i] * seconds;
            debrisY[i] += debrisVelocityY[i] * seconds;
            if (debrisDeadline[i] > nowNanos) {
                continue;
            }
            debrisCount--;
            debrisX[i] = debrisX[debrisCount];
            debrisY[i] = debrisY[debrisCount];
            debrisVelocityX[i] = debrisVelocityX[debrisCount];
            debrisVelocityY[i] = debrisVelocityY[debrisCount];
            debrisDeadline[i] = debrisDeadline[debrisCount];
        }
    }
}
