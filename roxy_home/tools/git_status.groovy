def pb = new ProcessBuilder("git", "status")
pb.directory(sandbox.getRoot().toFile())
pb.redirectErrorStream(true)
def process = pb.start()
def output = process.inputStream.text
process.waitFor()
return output
