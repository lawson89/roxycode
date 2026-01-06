package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@ScriptService("previewService")
@Singleton
public class PreviewService {
    private static final Logger LOG = LoggerFactory.getLogger(PreviewService.class);

    private final Sandbox sandbox;
    private final BuildToolService buildToolService;

    @Inject
    public PreviewService(Sandbox sandbox, BuildToolService buildToolService) {
        this.sandbox = sandbox;
        this.buildToolService = buildToolService;
    }

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

            // Save to temp file
            File outputFile = File.createTempFile("roxy_preview_", ".png");
            ImageIO.write(image, "png", outputFile);

            return outputFile.getAbsolutePath();
        } finally {
            // 5. Cleanup
            if (appProcess.isAlive()) {
                appProcess.destroyForcibly();
            }
        }
    }
}
