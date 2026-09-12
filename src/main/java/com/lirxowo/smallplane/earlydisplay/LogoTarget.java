package com.lirxowo.smallplane.earlydisplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.neoforged.fml.earlydisplay.render.ElementShader;
import net.neoforged.fml.earlydisplay.render.GlState;
import net.neoforged.fml.earlydisplay.render.LoadingScreenRenderer;
import net.neoforged.fml.earlydisplay.render.MaterializedTheme;
import net.neoforged.fml.earlydisplay.render.RenderContext;
import net.neoforged.fml.earlydisplay.render.Texture;
import net.neoforged.fml.earlydisplay.render.elements.RenderElement;
import net.neoforged.fml.earlydisplay.theme.ImageLoader;
import net.neoforged.fml.earlydisplay.theme.NativeBuffer;
import net.neoforged.fml.earlydisplay.theme.TextureScaling;
import net.neoforged.fml.earlydisplay.theme.Theme;
import net.neoforged.fml.earlydisplay.theme.ThemeMojangLogoElement;
import net.neoforged.fml.earlydisplay.theme.UncompressedImage;
import net.neoforged.fml.earlydisplay.util.Bounds;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces the Mojang logo of the early loading screen with a destructible version of it.
 * <p>
 * The logo is drawn as a grid of cells instead of the two quads vanilla uses, so individual cells
 * can be shot away. All surviving cells are still submitted as a single draw call. Cells that hold
 * no visible pixels are never drawn and cannot be hit.
 */
final class LogoTarget extends RenderElement {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogoTarget.class);

    /** The logo is shipped with the game, not with the theme, exactly like the vanilla element. */
    private static final String LOGO_PATH = "assets/minecraft/textures/gui/title/mojangstudios.png";
    /** Intrinsic size the vanilla layout resolves the logo against. */
    private static final int LOGO_LAYOUT_WIDTH = 512;
    private static final int LOGO_LAYOUT_HEIGHT = 128;
    /**
     * The texture stores the logo as two stacked halves that are drawn side by side, so the grid is
     * split into a left and a right half as well.
     */
    private static final int COLUMNS_PER_HALF = 32;
    private static final int ROWS = 16;
    private static final int COLUMNS = COLUMNS_PER_HALF * 2;
    private static final int HALVES = 2;
    private static final int ALPHA_OFFSET = 3;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int OPAQUE_ALPHA = 128;
    /** A cell counts as a target once this many of its texture pixels are opaque. */
    private static final int OPAQUE_PIXELS_PER_TARGET = 8;
    private static final int LOGO_COLOR = 0xFFFFFFFF;

    private final QuadBatch batch;
    private final @Nullable Texture texture;
    private final Bounds bounds;
    private final float cellWidth;
    private final float cellHeight;
    private final boolean[] target;
    private final boolean[] alive;

    LogoTarget(ThemeMojangLogoElement element, MaterializedTheme theme, QuadBatch batch) {
        super(element, theme);
        this.batch = batch;
        this.bounds = resolveBounds(
                LoadingScreenRenderer.LAYOUT_WIDTH, LoadingScreenRenderer.LAYOUT_HEIGHT, LOGO_LAYOUT_WIDTH, LOGO_LAYOUT_HEIGHT);
        this.cellWidth = bounds.width() / COLUMNS;
        this.cellHeight = bounds.height() / ROWS;
        this.target = new boolean[COLUMNS * ROWS];
        this.alive = new boolean[COLUMNS * ROWS];
        Arrays.fill(alive, true);

        UncompressedImage image = loadLogo();
        if (image != null) {
            try (image) {
                markTargets(image);
                this.texture = Texture.create(image, "smallplane mojang logo", new TextureScaling.Stretch(LOGO_LAYOUT_WIDTH, LOGO_LAYOUT_HEIGHT, true), null);
            }
        } else {
            this.texture = null;
            LOGGER.warn("Mojang logo not found on the classpath, the loading screen target is disabled");
        }
    }

    @Override
    public void render(RenderContext context) {
        if (texture == null) {
            return;
        }

        GlState.bindTexture2D(texture.textureId());
        GlState.bindSampler(0);
        ElementShader shader = context.bindShader(Theme.SHADER_GUI);
        shader.setUniform1i(ElementShader.UNIFORM_SAMPLER0, 0);

        batch.begin();
        for (int row = 0; row < ROWS; row++) {
            float y0 = bounds.top() + row * cellHeight;
            float v0 = row / (float) ROWS / HALVES;
            float v1 = (row + 1) / (float) ROWS / HALVES;
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                if (!target[index] || !alive[index]) {
                    continue;
                }
                int half = column / COLUMNS_PER_HALF;
                int columnInHalf = column - half * COLUMNS_PER_HALF;
                float x0 = bounds.left() + column * cellWidth;
                batch.textured(
                        x0, y0, x0 + cellWidth, y0 + cellHeight,
                        columnInHalf / (float) COLUMNS_PER_HALF, v0 + half / (float) HALVES,
                        (columnInHalf + 1) / (float) COLUMNS_PER_HALF, v1 + half / (float) HALVES,
                        LOGO_COLOR);
            }
        }
        batch.draw();
    }

    /**
     * Destroys the first cell hit by a bullet that travelled upwards from {@code fromY} to
     * {@code toY} during one frame. A single frame can cover more than one cell, so the whole
     * segment is tested instead of just its end, otherwise fast bullets tunnel through cells.
     *
     * @return the index of the cell that was destroyed, or {@code -1} when nothing was hit
     */
    int shoot(float x, float fromY, float toY) {
        if (texture == null || x < bounds.left() || x >= bounds.right()) {
            return -1;
        }
        float enter = Math.min(fromY, bounds.bottom());
        float exit = Math.max(toY, bounds.top());
        if (exit > enter) {
            return -1;
        }

        int column = (int) ((x - bounds.left()) / cellWidth);
        int firstRow = Math.clamp((int) ((enter - bounds.top()) / cellHeight), 0, ROWS - 1);
        int lastRow = Math.clamp((int) ((exit - bounds.top()) / cellHeight), 0, ROWS - 1);
        for (int row = firstRow; row >= lastRow; row--) {
            int index = row * COLUMNS + column;
            if (target[index] && alive[index]) {
                alive[index] = false;
                return index;
            }
        }
        return -1;
    }

    float cellCenterX(int index) {
        return bounds.left() + (index % COLUMNS + 0.5f) * cellWidth;
    }

    float cellCenterY(int index) {
        return bounds.top() + (index / COLUMNS + 0.5f) * cellHeight;
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close();
        }
    }

    private void markTargets(UncompressedImage image) {
        ByteBuffer pixels = image.imageData();
        int imageWidth = image.width();
        int halfHeight = image.height() / HALVES;
        int pixelsPerColumn = imageWidth / COLUMNS_PER_HALF;
        int pixelsPerRow = halfHeight / ROWS;

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int half = column / COLUMNS_PER_HALF;
                int firstX = (column - half * COLUMNS_PER_HALF) * pixelsPerColumn;
                int firstY = half * halfHeight + row * pixelsPerRow;
                int opaque = 0;
                for (int y = firstY; y < firstY + pixelsPerRow && opaque < OPAQUE_PIXELS_PER_TARGET; y++) {
                    int rowOffset = y * imageWidth * BYTES_PER_PIXEL;
                    for (int x = firstX; x < firstX + pixelsPerColumn; x++) {
                        if ((pixels.get(rowOffset + x * BYTES_PER_PIXEL + ALPHA_OFFSET) & 0xFF) >= OPAQUE_ALPHA) {
                            opaque++;
                        }
                    }
                }
                target[row * COLUMNS + column] = opaque >= OPAQUE_PIXELS_PER_TARGET;
            }
        }
    }

    private static @Nullable UncompressedImage loadLogo() {
        ClassLoader[] classLoaders = { Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader() };
        for (ClassLoader classLoader : classLoaders) {
            try (NativeBuffer buffer = NativeBuffer.loadFromClasspath(LOGO_PATH, classLoader)) {
                if (ImageLoader.tryLoadImage("mojang logo", null, buffer) instanceof ImageLoader.Result.Success(UncompressedImage image)) {
                    return image;
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to read {}", LOGO_PATH, e);
            }
        }
        return null;
    }
}
