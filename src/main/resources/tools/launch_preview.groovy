import org.zeroturnaround.exec.ProcessExecutor
import java.awt.Robot
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit

// 1. Compile
// Note: We use 'mvn' from the path. Ensure maven is in your system PATH or use ./mvnw
new ProcessExecutor()
        .command("mvn", "compile")
        .directory(sandbox.getProjectRoot().toFile())
        .redirectOutput(System.out)
        .execute()

// 2. Launch in background
// We assume we are running the 'roxycode' jar or class.
// For MVP, we run the current classpath Application.
String classpath = System.getProperty("java.class.path")
String javaHome = System.getProperty("java.home") + "/bin/java"

def process = new ProcessBuilder(javaHome, "-cp", classpath, "org.roxycode.Application")
        .directory(sandbox.getProjectRoot().toFile())
        .start()

try {
    // 3. Wait for UI to render
    Thread.sleep(5000)

    // 4. Take Screenshot
    // Headless environment check: This will fail in strict headless CI, but works on desktop
    Robot robot = new Robot()
    Rectangle captureSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
    def image = robot.createScreenCapture(captureSize)

    // Save to temp file
    File outputFile = File.createTempFile("roxy_preview_", ".png")
    ImageIO.write(image, "png", outputFile)

    return outputFile.getAbsolutePath()

} finally {
    // 5. Cleanup
    if (process.isAlive()) {
        process.destroyForcibly()
    }
}