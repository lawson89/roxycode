# 🛑 CRITICAL CONTEXT: Swing HTML & CSS Limitations

When modifying UI templates (like `ChatHtmlTemplates.java`), CSS files, or suggesting UI styling, you MUST remember that RoxyCode uses Swing's native `JTextPane` with `HTMLEditorKit`. 

**This renderer is permanently stuck in 1999. It only supports HTML 3.2 and very basic CSS 1.0.**

## ❌ FORBIDDEN (Do NOT use these)
- **Modern layouts:** `display: flex`, `display: grid`, `flex-direction`, `justify-content`, `align-items`.
- **Modern styling:** `border-radius` (you cannot round corners in CSS), `box-shadow`, `opacity`, gradients.
- **Advanced selectors:** `calc()`, `var(--vars)`, `:hover`, `:nth-child`, pseudo-elements (`::before`, `::after`).
- **Semantic HTML5 tags:** `<article>`, `<section>`, `<nav>`, `<header>`, `<footer>`.

## ✅ REQUIRED WORKAROUNDS
- **Layouts:** You MUST use old-school HTML tables for structural layouts and side-by-side elements. Use `<table width="100%" border="0" cellpadding="0" cellspacing="0">`, `<tr>`, and `<td>`.
- **Alignment:** Use HTML attributes on table cells (e.g., `<td valign="middle" align="right">`) rather than CSS.
- **Rounded Elements:** Since `border-radius` is ignored, if an image or panel needs rounded corners, it must be drawn manually in Java using `Graphics2D` (e.g., overriding `paintComponent` with `RenderingHints.KEY_ANTIALIASING`), or use a pre-cropped transparent PNG.
- **Typography and Color:** Basic CSS for fonts (`font-family`, `font-size`, `font-weight`) and colors (`color`, `background-color`) works perfectly. Use hex codes (`#FFFFFF`).

*Rule of thumb: Code your HTML/CSS as if you are building an email template for Microsoft Outlook.*