package com.lirxowo.smallplane.earlydisplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.theme.Theme;
import net.neoforged.fml.earlydisplay.theme.ThemeIds;
import net.neoforged.fml.earlydisplay.theme.ThemeLoader;
import net.neoforged.fml.earlydisplay.theme.UncompressedImage;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmallPlaneWindowProvider extends DisplayWindow {
    static final String PROVIDER_NAME = "smallplane";
    static final String FML_PROVIDER_NAME = "fmlearlywindow";

    private static final Logger LOGGER = LoggerFactory.getLogger(SmallPlaneWindowProvider.class);

    private static final String ARGUMENT_WIDTH = "width";
    private static final String ARGUMENT_HEIGHT = "height";
    private static final String OPTION_DARK_BACKGROUND = "darkMojangStudiosBackground:";
    private static final String OPTION_EXCLUSIVE_FULLSCREEN = "exclusiveFullscreen:";
    private static final String OPTION_LANGUAGE = "lang:";
    private static final String OPTIONS_FILE = "options.txt";
    private static final String THEME_DIRECTORY = "fml";
    private static final String DARK_MODE_PROPERTY = "fml.earlyWindowDarkMode";
    private static final String WINDOW_CLASS_NAME = "Minecraft*";
    private static final String RENDER_THREAD_NAME = "smallplane-loadingscreen";

    private static final int GL_MAJOR_VERSION = 3;
    private static final int GL_MINOR_VERSION = 3;
    private static final long RENDERER_STARTUP_DELAY_MS = 1L;
    private static final long RENDERER_TIMEOUT_SECONDS = 30L;
    private static final long HANDOFF_FRAME_SLEEP_MS = 2L;

    private final Runnable noRepaint = () -> {};
    private final ProgressMeter mainProgress;

    private @Nullable EarlyText text;
    private @Nullable PlaneGame game;
    private @Nullable Theme theme;
    private @Nullable ScheduledExecutorService renderScheduler;
    private @Nullable ScheduledFuture<PlaneLoadingScreen> screenFuture;

    private long window;
    private int windowWidth;
    private int windowHeight;
    private boolean darkMode;
    private boolean borderless = true;
    private boolean maximized;
    private @Nullable String minecraftVersion;
    private @Nullable String neoForgeVersion;

    private Runnable repaintTick = noRepaint;
    private volatile boolean closed;
    private volatile boolean handoffGateDisabled;

    public SmallPlaneWindowProvider() {

        mainProgress = StartupNotificationManager.getCurrentProgress().getLast();
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public void initialize(ProgramArgs arguments) {
        discardUnusedProgressBars();

        windowWidth = intArgument(arguments, ARGUMENT_WIDTH, FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH));
        windowHeight = intArgument(arguments, ARGUMENT_HEIGHT, FMLConfig.getIntConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT));
        FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_WIDTH, windowWidth);
        FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_HEIGHT, windowHeight);
        maximized = FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_MAXIMIZED);

        String language = readOptions();
        text = EarlyText.forLocale(language);
        game = new PlaneGame(text);
        theme = loadTheme();

        renderScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name(RENDER_THREAD_NAME)
                .daemon()
                .uncaughtExceptionHandler((thread, error) -> LOGGER.error("Uncaught error on the loading screen thread", error))
                .factory());

        createWindow();

        screenFuture = renderScheduler.schedule(
                () -> new PlaneLoadingScreen(renderScheduler, window, theme, themeDirectory(), this::versionLabel, game),
                RENDERER_STARTUP_DELAY_MS,
                TimeUnit.MILLISECONDS);

        updateProgress(text.get(EarlyText.KEY_INITIALIZING));
    }

    @Override
    public void setMinecraftVersion(String version) {
        minecraftVersion = version;
        super.setMinecraftVersion(version);
    }

    @Override
    public void setNeoForgeVersion(String version) {
        neoForgeVersion = version;
        super.setNeoForgeVersion(version);
    }

    @Override
    public void periodicTick() {
        if (screenFuture.state() == Future.State.FAILED) {
            throw new IllegalStateException("The early loading screen failed to initialize", screenFuture.exceptionNow());
        }
        GLFW.glfwPollEvents();
        if (!closed) {
            repaintTick.run();
        }
    }

    @Override
    public long takeOverGlfwWindow() {
        PlaneLoadingScreen screen = awaitScreen();
        updateProgress(text.get(EarlyText.KEY_INITIALIZING));
        try {
            screen.stopAutomaticRendering();
        } catch (TimeoutException e) {
            crash("The loading screen renderer did not release the OpenGL context in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        completeProgress();

        GLFW.glfwMakeContextCurrent(window);
        if (!handoffGateDisabled && game.isSummoned()) {
            GL.createCapabilities();
            awaitPlayer(screen);
        }

        GLFW.glfwSwapInterval(0);
        GLFW.glfwSetWindowSizeCallback(window, null).close();
        GLFW.glfwSetKeyCallback(window, null).close();
        repaintTick = screen::renderToScreen;
        restoreDefaultProvider();
        return window;
    }

    @Override
    public void displayFatalErrorAndExit(List<ModLoadingIssue> issues,
            @Nullable Path modsFolder,
            @Nullable Path logFile,
            @Nullable Path crashReportFile) {
        handoffGateDisabled = true;
        super.displayFatalErrorAndExit(issues, modsFolder, logFile, crashReportFile);
    }

    @Override
    public void renderToFramebuffer() {
        if (screenFuture.isDone()) {
            screenFuture.resultNow().renderToFramebuffer(System.nanoTime());
        }
    }

    @Override
    public int getFramebufferTextureId() {
        return 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        repaintTick = noRepaint;
        renderScheduler.shutdown();
        try {
            screenFuture.get().close();
        } catch (ExecutionException e) {
            LOGGER.error("Cannot close the loading screen since it failed to initialize", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        restoreDefaultProvider();
    }

    private void awaitPlayer(PlaneLoadingScreen screen) {
        game.holdHandoff();
        GLFW.glfwSwapInterval(1);
        while (!game.isHandoffReleased() && !GLFW.glfwWindowShouldClose(window)) {
            GLFW.glfwPollEvents();
            screen.renderToScreen();
            try {
                Thread.sleep(HANDOFF_FRAME_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private PlaneLoadingScreen awaitScreen() {
        try {
            return screenFuture.get(RENDERER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the loading screen", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The early loading screen failed to initialize", e.getCause());
        } catch (TimeoutException e) {
            crash("The early loading screen did not start within " + RENDERER_TIMEOUT_SECONDS + " seconds.");
            throw new IllegalStateException(e);
        }
    }

    private void createWindow() {
        if (!GLFW.glfwInit()) {
            crash("We are unable to initialize the graphics system.\nglfwInit failed.\n");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GL_MAJOR_VERSION);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GL_MINOR_VERSION);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GL32C.GL_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_SOFT_FULLSCREEN, borderless ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHintString(GLFW.GLFW_X11_CLASS_NAME, WINDOW_CLASS_NAME);
        GLFW.glfwWindowHintString(GLFW.GLFW_X11_INSTANCE_NAME, WINDOW_CLASS_NAME);

        window = GLFW.glfwCreateWindow(windowWidth, windowHeight, text.get(EarlyText.KEY_WINDOW_TITLE), 0L, 0L);
        if (window == 0L) {
            crash("Failed to create a window.\nglfwCreateWindow failed.\n");
        }
        if (maximized) {
            GLFW.glfwMaximizeWindow(window);
        }
        centerWindow();
        applyWindowIcon();

        GLFW.glfwSetWindowSizeCallback(window, this::onResize);
        GLFW.glfwSetKeyCallback(window, this::onKey);
        GLFW.glfwShowWindow(window);
        GLFW.glfwPollEvents();
    }

    private void centerWindow() {
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(window, width, height);
        windowWidth = width[0];
        windowHeight = height[0];

        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode videoMode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
        if (videoMode == null) {
            LOGGER.warn("No primary monitor found, leaving the window where GLFW put it");
            return;
        }
        int[] monitorX = new int[1];
        int[] monitorY = new int[1];
        GLFW.glfwGetMonitorPos(monitor, monitorX, monitorY);
        GLFW.glfwSetWindowPos(
                window,
                (videoMode.width() - windowWidth) / 2 + monitorX[0],
                (videoMode.height() - windowHeight) / 2 + monitorY[0]);
    }

    private void applyWindowIcon() {
        try (GLFWImage.Buffer icons = GLFWImage.malloc(1);
                GLFWImage icon = GLFWImage.malloc();
                UncompressedImage image = theme.windowIcon().loadAsImage(themeDirectory())) {
            icons.put(icon.set(image.width(), image.height(), image.imageData()));
            icons.flip();
            GLFW.glfwSetWindowIcon(window, icons);
        } catch (Exception e) {
            LOGGER.error("Failed to set the window icon", e);
        }
    }

    private void onResize(long resized, int width, int height) {
        if (resized == window && width != 0 && height != 0) {
            windowWidth = width;
            windowHeight = height;
        }
    }

    private void onKey(long focused, int key, int scancode, int action, int modifiers) {
        game.onKey(key, action);
    }

    private String readOptions() {
        boolean[] dark = { false };
        boolean[] windowed = { true };
        String[] language = { null };
        try (Stream<String> lines = Files.lines(FMLPaths.GAMEDIR.get().resolve(OPTIONS_FILE))) {
            lines.forEach(line -> {
                if (line.startsWith(OPTION_DARK_BACKGROUND)) {
                    dark[0] = line.toLowerCase(Locale.ROOT).endsWith("true");
                } else if (line.startsWith(OPTION_EXCLUSIVE_FULLSCREEN)) {
                    windowed[0] = line.toLowerCase(Locale.ROOT).endsWith("false");
                } else if (line.startsWith(OPTION_LANGUAGE)) {
                    language[0] = line.substring(OPTION_LANGUAGE.length()).trim();
                }
            });
        } catch (NoSuchFileException ignored) {

        } catch (IOException e) {
            LOGGER.warn("Failed to read {}", OPTIONS_FILE, e);
        }
        darkMode = Boolean.getBoolean(DARK_MODE_PROPERTY) || dark[0];
        borderless = windowed[0];
        return language[0];
    }

    private Theme loadTheme() {
        String configured = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_LOADING_SCREEN_THEME);
        String themeId = configured.isEmpty() ? defaultThemeId() : configured;
        try {
            return ThemeLoader.load(themeDirectory(), themeId);
        } catch (Exception e) {
            LOGGER.error("Failed to load the theme {}, falling back to the built-in one", themeId, e);
            return Theme.createDefaultTheme();
        }
    }

    private String defaultThemeId() {
        LocalDate today = LocalDate.now();
        if (today.getMonth() == Month.APRIL && today.getDayOfMonth() == 1) {
            return darkMode ? ThemeIds.APRIL_FOOLS_DARK_MODE : ThemeIds.APRIL_FOOLS;
        }
        return darkMode ? ThemeIds.DARK_MODE : ThemeIds.DEFAULT;
    }

    private String versionLabel() {
        StringBuilder label = new StringBuilder();
        if (minecraftVersion != null) {
            label.append(minecraftVersion);
        }
        if (neoForgeVersion != null) {
            if (!label.isEmpty()) {
                label.append('-');
            }
            label.append(neoForgeVersion.split("-")[0]);
        }
        return label.toString();
    }

    private void restoreDefaultProvider() {
        if (PROVIDER_NAME.equals(FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER))) {
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, FML_PROVIDER_NAME);
        }
    }

    private void discardUnusedProgressBars() {
        for (ProgressMeter bar : StartupNotificationManager.getCurrentProgress()) {
            if (bar != mainProgress && bar.name().isEmpty() && bar.steps() == 0) {
                StartupNotificationManager.popBar(bar);
            }
        }
    }

    private static Path themeDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(THEME_DIRECTORY);
    }

    private static int intArgument(ProgramArgs arguments, String key, int fallback) {
        String value = arguments.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring unparsable --{} {}", key, value);
            }
        }
        return fallback;
    }
}
