package de.cteichert.AIStoryWriter.model;

public record StoryRequest(
        String title,
        String genre,
        String additonalTextPrompt,
        String additonalImagePrompt,
        int scenes,
        String tone,
        String model  // optional: Name des lokal gehosteten Modells, z.B. "gpt-oss-20b"
) {}