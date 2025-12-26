
def gitArgs = ["git", "diff"]
if (cached) {
    gitArgs.add("--cached")
}
if (path) {
    gitArgs.add(path)
}

def processBuilder = new ProcessBuilder(gitArgs)
processBuilder.directory(sandbox.getRoot().toFile())
processBuilder.redirectErrorStream(true)

def process = processBuilder.start()
def output = process.inputStream.text
def exitCode = process.waitFor()

if (exitCode == 0) {
    if (output.trim().isEmpty()) {
        return "No changes found."
    }
    return output
} else {
    return "Error running git diff:\n" + output
}
