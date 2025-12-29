try {
    // 'git' is bound to GitService
    var result = git.getStatus(sandbox.getRoot());
    if (result === "") {
        "On branch " + git.getCurrentBranch(sandbox.getRoot()) + "\nnothing to commit, working tree clean";
    } else {
        result;
    }
} catch (e) {
    "❌ Error executing git status: " + e.message;
}
