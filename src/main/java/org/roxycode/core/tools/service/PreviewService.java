package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for capturing screenshots of the application.
 */
@ScriptService("previewService")
@Singleton
@LLMDoc("Service for capturing screenshots of the application.")
public class PreviewService {
    private static final Logger LOG = LoggerFactory.getLogger(PreviewService.class);

    private final Sandbox sandbox;
    private final BuildToolService buildToolService;

    @Inject
    public PreviewService(Sandbox sandbox, BuildToolService buildToolService) {
        this.sandbox = sandbox;
        this.buildToolService = buildToolService;
    }

    /**
     * Compiles the project, launches the application, takes a full-screen screenshot, and terminates the application.
     *
     * @return The absolute path to the generated screenshot (PNG).
     * @throws Exception If compilation fails, or an error occurs during launch or capture.
     */
    @LLMDoc("Compiles the project, launches it, takes a screenshot, and returns the path to the image")
    public String launchAndScreenshot() throws Exception {
        LOG.info("Launching Preview Service to take screenshot.");
        // 1. Compile
        String compileResult = buildToolService.compile();
        if (compileResult.contains("FAILED")) {
            throw new Exception("Compilation failed:\n" + compileResult);
        }

        // 2. Launch in background
        String classpath = System.getProperty("java.class.path");
        String javaHome = System.getProperty("java.home") + "/bin/java";

        List<String> command = new ArrayList<>();
        command.add(javaHome);
        command.add("-cp");
        command.add(classpath);
        command.add("org.roxycode.Application");

        ProcessBuilder appProcessBuilder = new ProcessBuilder(command);
        appProcessBuilder.directory(sandbox.getRoot().toFile());
        Process appProcess = appProcessBuilder.start();

        try {
            // 3. Wait for UI to render
            Thread.sleep(5000);

            // 4. Take Screenshot
            Robot robot = new Robot();
            Rectangle captureSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = robot.createScreenCapture(captureSize);

            // Convert to Data URI
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            return "data:image/png;base64," + base64;
        } finally {
            // 5. Cleanup
            if (appProcess.isAlive()) {
                appProcess.destroyForcibly();
            }
        }
    }
}
