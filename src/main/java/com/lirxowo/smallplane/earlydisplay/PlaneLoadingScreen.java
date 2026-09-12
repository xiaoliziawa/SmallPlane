package com.lirxowo.smallplane.earlydisplay;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ZERO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.neoforged.fml.earlydisplay.render.ElementShader;
import net.neoforged.fml.earlydisplay.render.EarlyFramebuffer;
import net.neoforged.fml.earlydisplay.render.GlState;
import net.neoforged.fml.earlydisplay.render.LoadingScreenRenderer;
import net.neoforged.fml.earlydisplay.render.MaterializedTheme;
import net.neoforged.fml.earlydisplay.render.RenderContext;
import net.neoforged.fml.earlydisplay.render.SimpleBufferBuilder;
import net.neoforged.fml.earlydisplay.render.elements.ImageElement;
import net.neoforged.fml.earlydisplay.render.elements.LabelElement;
import net.neoforged.fml.earlydisplay.render.elements.PerformanceElement;
import net.neoforged.fml.earlydisplay.render.elements.ProgressBarsElement;
import net.neoforged.fml.earlydisplay.render.elements.RenderElement;
import net.neoforged.fml.earlydisplay.render.elements.StartupLogElement;
import net.neoforged.fml.earlydisplay.theme.Theme;
import net.neoforged.fml.earlydisplay.theme.ThemeColor;
import net.neoforged.fml.earlydisplay.theme.elements.ThemeElement;
import net.neoforged.fml.earlydisplay.theme.elements.ThemeImageElement;
import net.neoforged.fml.earlydisplay.theme.elements.ThemeLabelElement;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PlaneLoadingScreen implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaneLoadingScreen.class);

    private static final String VERSION_PLACEHOLDER = "version";
    private static final int VERTEX_BUFFER_BYTES = 8192;
    private static final long RENDER_INTERVAL_MS = 16L;
    private static final long ANIMATION_INTERVAL_MS = 50L;
    private static final long CONTEXT_HANDOVER_TIMEOUT_SECONDS = 5L;
    private static final long IDLE_FRAME_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long ACTIVE_FRAME_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(RENDER_INTERVAL_MS);

    private final long window;
    private final PlaneGame game;
    private final MaterializedTheme theme;
    private final EarlyFramebuffer framebuffer;
    private final SimpleBufferBuilder buffer = new SimpleBufferBuilder("smallplane", VERTEX_BUFFER_BYTES);
    private final QuadBatch batch = new QuadBatch(buffer);
    private final List<RenderElement> elements;
    private final @Nullable LogoTarget logo;
    private final Semaphore renderLock = new Semaphore(1);
    private final ScheduledFuture<?> automaticRendering;
    private final int[] framebufferWidth = new int[1];
    private final int[] framebufferHeight = new int[1];

    private volatile int animationFrame;
    private long nextFrameNanos;

    PlaneLoadingScreen(ScheduledExecutorService scheduler,
            long window,
            Theme themeDefinition,
            @Nullable Path themeDirectory,
            Supplier<String> versionLabel,
            PlaneGame game) {
        this.window = window;
        this.game = game;

        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
        GlState.readFromOpenGL();

        this.theme = MaterializedTheme.materialize(themeDefinition, themeDirectory);
        this.logo = themeDefinition.loadingScreen().mojangLogo().visible()
                ? new LogoTarget(themeDefinition.loadingScreen().mojangLogo(), this.theme, batch)
                : null;
        this.elements = createElements(versionLabel);
        this.framebuffer = new EarlyFramebuffer(LoadingScreenRenderer.LAYOUT_WIDTH, LoadingScreenRenderer.LAYOUT_HEIGHT);

        ThemeColor background = themeDefinition.colorScheme().screenBackground();
        GlState.clearColor(background.r(), background.g(), background.b(), 1f);
        GL32C.glClear(GL_COLOR_BUFFER_BIT);
        GlState.enableBlend(true);
        GlState.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GLFW.glfwMakeContextCurrent(0);

        this.automaticRendering = scheduler.scheduleWithFixedDelay(
                this::renderToScreen, RENDER_INTERVAL_MS, RENDER_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> animationFrame++, ANIMATION_INTERVAL_MS, ANIMATION_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void renderToScreen() {
        if (!renderLock.tryAcquire()) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (now < nextFrameNanos) {
                return;
            }
            nextFrameNanos = now + (game.isSummoned() ? ACTIVE_FRAME_INTERVAL_NANOS : IDLE_FRAME_INTERVAL_NANOS);

            GLFW.glfwMakeContextCurrent(window);
            GlState.readFromOpenGL();
            GlState.StateSnapshot backup = GlState.createSnapshot();

            GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            framebuffer.resize(framebufferWidth[0], framebufferHeight[0]);

            renderToFramebuffer(now);

            GlState.viewport(0, 0, framebufferWidth[0], framebufferHeight[0]);
            framebuffer.blitToScreen(theme.theme().colorScheme().screenBackground(), framebufferWidth[0], framebufferHeight[0]);
            GLFW.glfwSwapBuffers(window);

            GlState.applySnapshot(backup);
        } catch (Throwable t) {
            LOGGER.error("Unexpected error while rendering the loading screen", t);
        } finally {
            if (!automaticRendering.isCancelled()) {
                GLFW.glfwMakeContextCurrent(0);
            }
            renderLock.release();
        }
    }

    void renderToFramebuffer(long nowNanos) {
        GlState.readFromOpenGL();
        GlState.StateSnapshot backup = GlState.createSnapshot();
        framebuffer.activate();

        float layoutAspect = LoadingScreenRenderer.LAYOUT_WIDTH / (float) LoadingScreenRenderer.LAYOUT_HEIGHT;
        float windowAspect = framebuffer.width() / (float) framebuffer.height();
        int offsetX = 0;
        int offsetY = 0;
        float scale;
        if (windowAspect > layoutAspect) {
            int fittedWidth = (int) (layoutAspect * framebuffer.height());
            offsetX = (framebuffer.width() - fittedWidth) / 2;
            GlState.viewport(offsetX, 0, fittedWidth, framebuffer.height());
            scale = framebuffer.height() / (float) LoadingScreenRenderer.LAYOUT_HEIGHT;
        } else {
            int fittedHeight = (int) (framebuffer.width() / layoutAspect);
            offsetY = (framebuffer.height() - fittedHeight) / 2;
            GlState.viewport(0, offsetY, framebuffer.width(), fittedHeight);
            scale = framebuffer.width() / (float) LoadingScreenRenderer.LAYOUT_WIDTH;
        }

        ThemeColor background = theme.theme().colorScheme().screenBackground();
        GlState.clearColor(background.r(), background.g(), background.b(), 1f);
        GL32C.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        GlState.enableBlend(true);
        GlState.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

        for (ElementShader shader : theme.shaders().values()) {
            shader.activate();
            if (shader.hasUniform(ElementShader.UNIFORM_SCREEN_SIZE)) {
                shader.setUniform2f(
                        ElementShader.UNIFORM_SCREEN_SIZE, LoadingScreenRenderer.LAYOUT_WIDTH, LoadingScreenRenderer.LAYOUT_HEIGHT);
            }
        }

        RenderContext context = new RenderContext(
                buffer,
                theme,
                LoadingScreenRenderer.LAYOUT_WIDTH,
                LoadingScreenRenderer.LAYOUT_HEIGHT,
                offsetX,
                offsetY,
                scale,
                animationFrame);

        game.update(nowNanos, logo);
        for (RenderElement element : elements) {
            element.render(context);
        }
        game.render(context, batch, theme.getFont(Theme.FONT_DEFAULT));

        framebuffer.deactivate();
        GlState.applySnapshot(backup);
    }

    void stopAutomaticRendering() throws TimeoutException, InterruptedException {
        if (automaticRendering.isCancelled()) {
            return;
        }
        if (!renderLock.tryAcquire(CONTEXT_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new TimeoutException();
        }
        automaticRendering.cancel(false);
        renderLock.release();
    }

    @Override
    public void close() {
        long previousContext = GLFW.glfwGetCurrentContext();
        boolean restoreContext = previousContext != window;
        if (restoreContext) {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
        }
        try {
            theme.close();
            for (RenderElement element : elements) {
                element.close();
            }
            framebuffer.close();
            buffer.close();
            SimpleBufferBuilder.destroy();
        } finally {
            if (restoreContext) {
                GLFW.glfwMakeContextCurrent(previousContext);
            }
        }
    }

    private List<RenderElement> createElements(Supplier<String> versionLabel) {
        List<RenderElement> elements = new ArrayList<>();
        var loadingScreen = theme.theme().loadingScreen();
        if (loadingScreen.background() != null && loadingScreen.background().visible()) {
            elements.add(new ImageElement(loadingScreen.background(), theme));
        }
        if (loadingScreen.performance().visible()) {
            elements.add(new PerformanceElement(loadingScreen.performance(), theme));
        }
        if (loadingScreen.startupLog().visible()) {
            elements.add(new StartupLogElement(loadingScreen.startupLog(), theme));
        }
        if (loadingScreen.progressBars().visible()) {
            elements.add(new ProgressBarsElement(loadingScreen.progressBars(), theme));
        }
        if (logo != null) {
            elements.add(logo);
        }
        for (Map.Entry<String, ? extends ThemeElement> entry : loadingScreen.decoration().entrySet()) {
            ThemeElement element = entry.getValue();
            if (!element.visible()) {
                continue;
            }
            RenderElement decoration = switch (element) {
                case ThemeImageElement image -> new ImageElement(image, theme);
                case ThemeLabelElement label -> new LabelElement(label, theme, () -> Map.of(VERSION_PLACEHOLDER, versionLabel.get()));
                default -> null;
            };
            if (decoration != null) {
                decoration.setId(entry.getKey());
                elements.add(decoration);
            }
        }
        return elements;
    }
}
