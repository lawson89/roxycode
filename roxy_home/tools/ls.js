var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var File = Java.type('java.io.File');

function listDirectory(path, indent) {
    var result = "";
    var files = Files.list(path).toArray();
    for (var i = 0; i < files.length; i++) {
        var file = files[i];
        var fileName = file.getFileName().toString();
        var prefix = indent + (i === files.length - 1 ? "└── " : "├── ");
        result += prefix + fileName + "\n";

        if (Files.isDirectory(file)) {
            result += listDirectory(file, indent + (i === files.length - 1 ? "    " : "│   "));
        }
    }
    return result;
}

var targetPath;
if (args.path) {
    targetPath = sandbox.getRoot().resolve(args.path);
} else {
    targetPath = sandbox.getRoot();
}

if (!Files.exists(targetPath)) {
    throw new Error("Path not found: " + args.path);
}

var lsResult = targetPath.getFileName().toString() + "/\n" + listDirectory(targetPath, "");

// The last evaluated expression is returned.
lsResult;
