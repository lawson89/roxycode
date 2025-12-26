import java.util.concurrent.TimeUnit

def isWindows = System.getProperty("os.name").toLowerCase().contains("win")
def mvnCommand = isWindows ? ".\\mvnw.cmd" : "./mvnw"

def processBuilder = new ProcessBuilder(mvnCommand, "test")
processBuilder.redirectErrorStream(true)
processBuilder.directory(new File(".")) // Project root

def process = processBuilder.start()
def output = new StringBuilder()
def reader = new BufferedReader(new InputStreamReader(process.getInputStream()))
String line
while ((line = reader.readLine()) != null) {
    output.append(line).append("\n")
}

def exitCode = process.waitFor()

if (exitCode != 0) {
    return "Tests failed. Exit code: " + exitCode + "\nOutput:\n" + output.toString()
}

return output.toString()