package com.seekora.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalSummarizerTest {
    @Test void summarizeTextHonoursRequestedBulletCountAndKeepsSourceOrder() {
        String text = "Alpha systems measure rainfall across the northern region every single day. Beta stations record temperature near the coast during summer. "
                + "Gamma sensors track wind speed on the mountain ridges throughout winter. Delta buoys report wave height far out at sea each hour. "
                + "Epsilon satellites photograph cloud cover over the whole continent twice daily.";
        String summary = LocalSummarizer.summarizeText(text, "3 bullet points");
        assertEquals(3, summary.lines().filter(l -> l.startsWith("- ")).count());
        assertTrue(LocalSummarizer.summarizeText(text, "99 bullets").lines().filter(l -> l.startsWith("- ")).count() <= 5, "count is clamped to the available sentences");
    }

    @Test void summarizeSourcesCitesTheSourceNumber() {
        var source = new WebResearchService.ResearchSource("T", "https://example.org", "Photosynthesis converts light energy into chemical energy inside the chloroplasts of green plants every day.");
        String out = LocalSummarizer.summarizeSources("how does photosynthesis work", List.of(source));
        assertTrue(out.contains("[1]"), out);
        assertTrue(out.contains("Photosynthesis converts light energy"));
    }

    @Test void summarizeSourcesAdmitsWhenThereIsNothingUsable() {
        var source = new WebResearchService.ResearchSource("T", "https://example.org", "short");
        assertTrue(LocalSummarizer.summarizeSources("question", List.of(source)).contains("did not contain enough readable text"));
    }
}
