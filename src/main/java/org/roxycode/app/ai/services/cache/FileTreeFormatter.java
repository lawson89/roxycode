package org.roxycode.app.ai.services.cache;

import java.util.*;

/**
 * Utility to format a list of file paths into a visual tree structure.
 */
public class FileTreeFormatter {

    /**
     * Formats the list of paths into a tree string.
     * @param paths The list of relative file paths.
     * @return A formatted tree string.
     */
    public static String format(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return ".";
        }

        // Sort paths to ensure consistent tree structure
        List<String> sortedPaths = new ArrayList<>(paths);
        Collections.sort(sortedPaths);

        TreeNode root = new TreeNode("");
        for (String path : sortedPaths) {
            String[] parts = path.split("/");
            TreeNode current = root;
            for (String part : parts) {
                if (!part.isEmpty()) {
                    current = current.getOrCreateChild(part);
                }
            }
        }

        StringBuilder sb = new StringBuilder(".\n");
        renderTree(root, "", sb);
        return sb.toString().trim();
    }

    private static void renderTree(TreeNode node, String prefix, StringBuilder sb) {
        List<TreeNode> children = new ArrayList<>(node.children.values());
        children.sort(Comparator.comparing(n -> n.name));

        for (int i = 0; i < children.size(); i++) {
            TreeNode child = children.get(i);
            boolean isLast = (i == children.size() - 1);
            
            sb.append(prefix);
            sb.append(isLast ? "└── " : "├── ");
            sb.append(child.name);
            if (!child.children.isEmpty()) {
                sb.append("/");
            }
            sb.append("\n");

            String nextPrefix = prefix + (isLast ? "    " : "│   ");
            renderTree(child, nextPrefix, sb);
        }
    }

    private static class TreeNode {
        String name;
        Map<String, TreeNode> children = new HashMap<>();

        TreeNode(String name) {
            this.name = name;
        }

        TreeNode getOrCreateChild(String part) {
            return children.computeIfAbsent(part, TreeNode::new);
        }
    }
}