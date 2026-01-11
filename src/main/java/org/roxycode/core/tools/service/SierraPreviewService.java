package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.roxycode.core.utils.ComponentScreenshot;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Service for validating and previewing Sierra UI definition files.
 */
@ScriptService("sierraPreviewService")
@Singleton
@LLMDoc("Service for validating and previewing Sierra UI definition files.")
public class SierraPreviewService {

    private final Sandbox sandbox;
    private SierraPreviewFrame frame;

    @Inject
    public SierraPreviewService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Validates a Sierra file by attempting to render it.
     *
     * @param pathStr The path to the Sierra file.
     * @return A message indicating whether the file is valid or contains errors.
     */
    @LLMDoc("Use this method to validate a Sierra file. Returns a string indicating whether the file is valid or not.")
    public String validateSierra(String pathStr) {
        Path path = sandbox.resolve(pathStr);
        if (frame == null) {
            frame = new SierraPreviewFrame(path);
        }

        try {
            frame.preview();
            frame.validate();
            close();
            return "Valid Sierra file: " + pathStr;
        } catch (Exception e) {
            return "Invalid Sierra file: " + pathStr + "\nError: " + e.getMessage();
        }
    }

    /**
     * Generates a preview image of a Sierra file.
     *
     * @param pathStr The path to the Sierra file.
     * @return The absolute path to the generated PNG image, or an error message.
     */
    @LLMDoc("Use this method to generate a preview image of a Sierra file. Returns the path to the generated PNG image.")
    public String previewSierra(String pathStr) {
        Path path = sandbox.resolve(pathStr);
        if (frame == null) {
            frame = new SierraPreviewFrame(path);
        }

        try {
            frame.preview();
            frame.revalidate();
            BufferedImage image = ComponentScreenshot.captureComponent(frame);


            // Convert to Data URI
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            close();
            return "data:image/png;base64," + base64;
        } catch (IOException e) {
            return "Invalid Sierra file: " + pathStr + "\nError: " + e.getMessage();
        }
    }

    /**
     * Closes the preview frame and releases resources.
     */
    public void close() {
        if (frame != null) {
            frame.close();
            frame = null;
        }
    }
}
