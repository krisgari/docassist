package dev.docassist.common.chunking;

import dev.docassist.common.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure-aware chunker.
 *
 * <ul>
 *   <li>Splits on headings first, so a chunk never spans two sections.</li>
 *   <li>Packs blocks (paragraphs, lists, tables, code) up to {@code targetTokens}.</li>
 *   <li>Never splits a table row, list or code block; an oversized table is split into row groups with the
 *       header repeated; an oversized paragraph is split on sentence boundaries.</li>
 *   <li>Every chunk records its section path and the ordinal of the first chunk in its section
 *       ({@code parentOrdinal}) so retrieval can expand small hits to their full section.</li>
 * </ul>
 */
public final class MarkdownChunker {

    private final int targetTokens;
    private final int hardCapTokens;

    public MarkdownChunker() {
        this(450, 800);
    }

    public MarkdownChunker(int targetTokens, int hardCapTokens) {
        this.targetTokens = targetTokens;
        this.hardCapTokens = hardCapTokens;
    }

    private enum Kind { PARAGRAPH, TABLE, CODE }

    private record Block(Kind kind, String text) {}

    public List<Chunk> chunk(String title, String markdown) {
        List<Chunk> out = new ArrayList<>();
        String[] headingStack = new String[7];
        List<Block> sectionBlocks = new ArrayList<>();
        String sectionPath = title;

        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        StringBuilder para = new StringBuilder();
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                flushParagraph(para, sectionBlocks);
                StringBuilder code = new StringBuilder(line).append('\n');
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) code.append(lines[i]);
                sectionBlocks.add(new Block(Kind.CODE, code.toString().strip()));
                i++;
                continue;
            }

            int level = headingLevel(trimmed);
            if (level > 0) {
                flushParagraph(para, sectionBlocks);
                emitSection(title, sectionPath, sectionBlocks, out);
                sectionBlocks = new ArrayList<>();
                String heading = trimmed.substring(level).trim();
                headingStack[level] = heading;
                for (int l = level + 1; l < headingStack.length; l++) headingStack[l] = null;
                sectionPath = buildPath(title, headingStack);
                i++;
                continue;
            }

            if (trimmed.startsWith("|")) {
                flushParagraph(para, sectionBlocks);
                StringBuilder table = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    table.append(lines[i].trim()).append('\n');
                    i++;
                }
                sectionBlocks.add(new Block(Kind.TABLE, table.toString().strip()));
                continue;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(para, sectionBlocks);
            } else {
                if (para.length() > 0) para.append('\n');
                para.append(trimmed);
            }
            i++;
        }
        flushParagraph(para, sectionBlocks);
        emitSection(title, sectionPath, sectionBlocks, out);
        return out;
    }

    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
        if (level == 0 || level > 6) return 0;
        return (trimmed.length() > level && trimmed.charAt(level) == ' ') ? level : 0;
    }

    private static String buildPath(String title, String[] stack) {
        StringBuilder sb = new StringBuilder(title);
        for (int l = 1; l < stack.length; l++) {
            String h = stack[l];
            if (h == null) continue;
            if (l == 1 && h.equalsIgnoreCase(title)) continue; // H1 usually repeats the title
            sb.append(" > ").append(h);
        }
        return sb.toString();
    }

    private static void flushParagraph(StringBuilder para, List<Block> blocks) {
        if (para.length() > 0) {
            blocks.add(new Block(Kind.PARAGRAPH, para.toString()));
            para.setLength(0);
        }
    }

    private void emitSection(String title, String path, List<Block> blocks, List<Chunk> out) {
        if (blocks.isEmpty()) return;
        List<Block> pieces = new ArrayList<>();
        for (Block b : blocks) {
            if (Text.estimateTokens(b.text()) <= hardCapTokens) {
                pieces.add(b);
            } else if (b.kind() == Kind.TABLE) {
                pieces.addAll(splitTable(b.text()));
            } else if (b.kind() == Kind.PARAGRAPH) {
                pieces.addAll(splitSentences(b.text()));
            } else {
                pieces.add(b); // code blocks are kept whole even when large
            }
        }

        String header = "Document: " + title + ". Section: " + path + ".";
        int parent = out.size();
        StringBuilder current = new StringBuilder();
        for (Block piece : pieces) {
            int combined = Text.estimateTokens(current + "\n\n" + piece.text());
            if (current.length() > 0 && combined > targetTokens) {
                out.add(new Chunk(out.size(), parent, path, header, current.toString(),
                        Text.estimateTokens(current.toString())));
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(piece.text());
        }
        if (current.length() > 0) {
            out.add(new Chunk(out.size(), parent, path, header, current.toString(),
                    Text.estimateTokens(current.toString())));
        }
    }

    private List<Block> splitTable(String table) {
        String[] rows = table.split("\n");
        List<Block> out = new ArrayList<>();
        if (rows.length < 3) return List.of(new Block(Kind.TABLE, table));
        String head = rows[0] + "\n" + rows[1];
        StringBuilder group = new StringBuilder(head);
        for (int r = 2; r < rows.length; r++) {
            if (Text.estimateTokens(group + "\n" + rows[r]) > targetTokens && group.length() > head.length()) {
                out.add(new Block(Kind.TABLE, group.toString()));
                group = new StringBuilder(head);
            }
            group.append('\n').append(rows[r]);
        }
        out.add(new Block(Kind.TABLE, group.toString()));
        return out;
    }

    private List<Block> splitSentences(String paragraph) {
        List<Block> out = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        for (String s : Text.sentences(paragraph)) {
            if (group.length() > 0 && Text.estimateTokens(group + " " + s) > targetTokens) {
                out.add(new Block(Kind.PARAGRAPH, group.toString()));
                group.setLength(0);
            }
            if (group.length() > 0) group.append(' ');
            group.append(s);
        }
        if (group.length() > 0) out.add(new Block(Kind.PARAGRAPH, group.toString()));
        return out;
    }
}
