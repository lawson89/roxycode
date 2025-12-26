def os = System.getProperty("os.name").toLowerCase()
def isWindows = os.contains("win")

// Determine the command based on OS
def command = isWindows ? ["mvnw.cmd", "clean", "compile"] : ["./mvnw", "clean", "compile"]

// Execute process in the project root
def processBuilder = new ProcessBuilder(command)
processBuilder.directory(sandbox.getRoot().toFile())
processBuilder.redirectErrorStream(true)

def process = processBuilder.start()
def output = process.inputStream.text
def exitCode = process.waitFor()

if (exitCode == 0) {
    return "✅ BUILD SUCCESS\n" + output.takeRight(500) // Return just the summary
} else {
    return "❌ BUILD FAILED. Fix these errors:\n" + output
}