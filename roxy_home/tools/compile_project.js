var System = Java.type("java.lang.System");
var os = System.getProperty("os.name").toLowerCase();
var isWindows = os.includes("win");

var command;
if (isWindows) {
    command = ["mvnw.cmd", "clean", "compile"];
} else {
    command = ["./mvnw", "clean", "compile"];
}

var ProcessBuilder = Java.type("java.lang.ProcessBuilder");
var File = Java.type("java.io.File");

var processBuilder = new ProcessBuilder(command);
processBuilder.directory(new File(sandbox.getRoot().toString()));
processBuilder.redirectErrorStream(true);

var process = processBuilder.start();
var BufferedReader = Java.type("java.io.BufferedReader");
var InputStreamReader = Java.type("java.io.InputStreamReader");

var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
var line;
var StringBuilder = Java.type("java.lang.StringBuilder");
var output = new StringBuilder();
while ((line = reader.readLine()) != null) {
    output.append(line).append("\n");
}

var exitCode = process.waitFor();

var resultString; 
if (exitCode == 0) {
    var fullOutput = output.toString();
    var summary = fullOutput.substring(Math.max(0, fullOutput.length - 500)); 
    resultString = "✅ BUILD SUCCESS\n" + summary;
} else {
    resultString = "❌ BUILD FAILED. Fix these errors:\n" + output.toString();
}
resultString;