var ProcessBuilder = Java.type('java.lang.ProcessBuilder');
var Scanner = Java.type('java.util.Scanner');
var Sandbox = Java.type('com.roxytool.sandbox.Sandbox');

var sandbox = Sandbox.getInstance();

var pb = new ProcessBuilder('git', 'status');
pb.directory(sandbox.getRoot().toFile());
pb.redirectErrorStream(true);
var process = pb.start();
var output = new Scanner(process.getInputStream(), "UTF-8").useDelimiter("\A").next();
process.waitFor();
output;