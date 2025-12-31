if (typeof args.paths === 'string') {
    var pathsArray = args.paths.split(',').map(function(s) { return s.trim(); });
    fs.readFiles(pathsArray);
} else {
    // Fallback if it somehow comes in as a list/array already (though definition says string)
    fs.readFiles(args.paths);
}
