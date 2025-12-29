try {
    // 'git' is bound to GitService
    var result = git.diff(sandbox.getRoot(), args.cached === "true" || args.cached === true, args.path);
    if (result === "") {
        "No changes found.";
    } else {
        result;
    }
} catch (e) {
    "❌ Error executing git diff: " + e.message;
}
