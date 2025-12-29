# Refactor MarkdownPane Icon Handling

## Goal
Refactor `MarkdownPane` to reduce code duplication related to image handling of icons by introducing a common helper method.

## Analysis
The methods `appendToolLog`, `appendStatus`, and `appendRoxyMarkdown` in `MarkdownPane.java` share duplicated logic for:
1. Creating a `FontIcon`.
2. Rendering the icon to a `BufferedImage`.
3. Storing the image in the document's `imageCache`.
4. Generating an HTML `<img>` tag.

## Plan
1.  [x] Define a private helper method `generateIconTag` in `MarkdownPane.java`.
    *   Signature: `private String generateIconTag(org.kordamp.ikonli.Ikon iconCode, int size, Color color, String namePrefix)`
    *   This method will encapsulate the icon generation and caching logic.
2.  [x] Refactor `appendToolLog` to use `generateIconTag`.
3.  [x] Refactor `appendStatus` to use `generateIconTag`.
4.  [x] Refactor `appendRoxyMarkdown` to use `generateIconTag`.
5.  [x] Verify compilation and run tests.

## Implementation Details
- The common method needs to access `getDocument()` which is available in the class.
- The `namePrefix` parameter helps in generating unique image names (e.g., "wrench", "status", "roxy").
- Note: `appendRoxyMarkdown` does not append `&nbsp;` after the image tag in the variable `imgTag`, whereas others do or treat it differently. The helper should probably just return the img tag, and the caller can append spaces if needed.
    - `appendToolLog`: `imgTag = "<img ...>&nbsp;";`
    - `appendStatus`: `imgTag = "<img ...>&nbsp;";`
    - `appendRoxyMarkdown`: `imgTag = "<img ...>";`
- The helper method will return the pure `<img>` tag.

## Checklist
- [x] Create helper method `generateIconTag`.
- [x] Update `appendToolLog`.
- [x] Update `appendStatus`.
- [x] Update `appendRoxyMarkdown`.
- [x] Compile and verify.
