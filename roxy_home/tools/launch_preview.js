// 1. Compile
var ProcessBuilder = Java.type('java.lang.ProcessBuilder');
var System = Java.type('java.lang.System');
var File = Java.type('java.io.File');

// Assuming sandbox.getRoot() returns a Path or File object
var projectRoot = sandbox.getRoot().toFile();

var compileProcessBuilder = new ProcessBuilder("mvn", "compile");
compileProcessBuilder.directory(projectRoot);
compileProcessBuilder.inheritIO(); // Redirects stdin, stdout, stderr to the current process
var compileProcess = compileProcessBuilder.start();
compileProcess.waitFor(); // Wait for compilation to complete

if (compileProcess.exitValue() !== 0) {
    throw new Error("Maven compile failed!");
}

// 2. Launch in background
var classpath = System.getProperty("java.class.path");
var javaHome = System.getProperty("java.home") + "/bin/java";

var appProcessBuilder = new ProcessBuilder(javaHome, "-cp", classpath, "org.roxycode.Application");
appProcessBuilder.directory(projectRoot);
// Optional: If you want to see the application's output, you can inheritIO, but for background, it might be noisy.
// For now, let's not inheritIO for the app process to keep it in the background.
var appProcess = appProcessBuilder.start();

try {
    // 3. Wait for UI to render
    Java.type('java.lang.Thread').sleep(5000);

    // 4. Take Screenshot
    var Robot = Java.type('java.awt.Robot');
    var Rectangle = Java.type('java.awt.Rectangle');
    var Toolkit = Java.type('java.awt.Toolkit');
    var ImageIO = Java.type('javax.imageio.ImageIO');

    var robot = new Robot();
    var captureSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    var image = robot.createScreenCapture(captureSize);

    // Save to temp file
    var outputFile = File.createTempFile("roxy_preview_", ".png");
    ImageIO.write(image, "png", outputFile);

    return outputFile.getAbsolutePath();

} finally {
    // 5. Cleanup
    if (appProcess.isAlive()) {
        appProcess.destroyForcibly();
    }
}