package dev.docassist.common.chunking;

/**
 * A retrievable unit. {@code contextHeader} is prepended to {@code body} for embedding and lexical search so
 * that short or pronoun-heavy chunks still carry their document and section context.
 */
public record Chunk(
        int ordinal,
        int parentOrdinal,
        String sectionPath,
        String contextHeader,
        String body,
        int tokenCount) {

    public String embeddingText() {
        return contextHeader + "\n" + body;
    }
}
