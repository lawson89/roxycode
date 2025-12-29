// 1. Compile
var ProcessBuilder = Java.type('java.lang.ProcessBuilder');
var System = Java.type('java.lang.System');
var File = Java.type('java.io.File');

// Assuming sandbox.getRoot() returns a Path or File object
var projectRoot = new File(sandbox.getRoot().toString());

var os = System.getProperty("os.name").toLowerCase();
var mvn = os.includes("win") ? "mvnw.cmd" : "./mvnw";

var compileProcessBuilder = new ProcessBuilder(mvn, "compile");
compileProcessBuilder.directory(projectRoot);
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
var appProcess = appProcessBuilder.start();

var resultPath = "";
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

    resultPath = outputFile.getAbsolutePath();

} finally {
    // 5. Cleanup
    if (appProcess.isAlive()) {
        appProcess.destroyForcibly();
    }
}
resultPath;