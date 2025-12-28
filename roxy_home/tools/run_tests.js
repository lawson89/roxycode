var System = Java.type('java.lang.System');
var ProcessBuilder = Java.type('java.lang.ProcessBuilder');
var File = Java.type('java.io.File');
var BufferedReader = Java.type('java.io.BufferedReader');
var InputStreamReader = Java.type('java.io.InputStreamReader');
var StringBuilder = Java.type('java.lang.StringBuilder');

var isWindows = System.getProperty("os.name").toLowerCase().includes("win");
var mvnCommand = isWindows ? ".\\mvnw.cmd" : "./mvnw";

var processBuilder = new ProcessBuilder(mvnCommand, "test");
processBuilder.redirectErrorStream(true);
processBuilder.directory(new File(".")); // Project root

var process = processBuilder.start();
var output = new StringBuilder();
var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
var line;
while ((line = reader.readLine()) != null) {
    output.append(line).append("\n");
}

var exitCode = process.waitFor();

var result;
if (exitCode !== 0) {
    result = "Tests failed. Exit code: " + exitCode + "\nOutput:\n" + output.toString();
} else {
    result = output.toString();
}
result;
