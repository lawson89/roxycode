(function() {
    var path = args.path || null;
    var limit = args.limit || 10;
    try {
        var log = git.log(sandbox.getRoot(), path, limit);
        if (!log || log.trim().length === 0) {
            return "No history found.";
        }
        return log;
    } catch (e) {
        return "Error retrieving git log: " + e.message;
    }
})();