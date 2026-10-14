package com.seekora.web;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LocalSummarizer {
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'])|\\n+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "that", "this", "from", "what", "when", "where", "which", "who", "why", "how", "does", "about", "into", "your", "are", "was", "were", "have", "has", "will", "can", "could", "would", "should", "their", "there", "they", "them", "then", "than", "you", "all", "any", "not", "but", "use", "using", "get", "give", "tell", "explain", "find", "please", "me", "it", "its", "is", "of", "in", "on", "to", "a", "an", "or", "as", "by", "be", "at", "do", "did", "i", "we", "our", "my", "some", "many", "more", "best", "information");
    private static final Pattern LOW_QUALITY = Pattern.compile("(?i)\\b(coordinates?|from wikipedia|main article|read more|arrow_outward|see also|jump to|navigation menu)\\b");
    private LocalSummarizer() { }

    public static String summarizeSources(String query, List<WebResearchService.ResearchSource> sources) {
        Set<String> queryTerms = terms(query);
        List<ScoredSentence> candidates = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            WebResearchService.ResearchSource source = sources.get(index);
            String[] sentences = SENTENCE_SPLIT.split(source.text());
            for (int order = 0; order < sentences.length; order++) {
                String sentence = sentences[order].replaceAll("\\[\\d+(?:,\\s*\\d+)*]", "")
                        .replaceAll("\\s*\\[edit]", "").replaceAll("\\s+", " ").trim();
                if (sentence.length() < 65 || sentence.length() > 360 || LOW_QUALITY.matcher(sentence).find()) continue;
                if (sentence.split("\\s+").length < 10) continue;
                Set<String> sentenceTerms = terms(sentence);
                long overlap = sentenceTerms.stream().filter(queryTerms::contains).count();
                if (!queryTerms.isEmpty() && overlap == 0) continue;
                double coverage = queryTerms.isEmpty() ? 0 : (double) overlap / queryTerms.size();
                double score = overlap * 3.0 + coverage * 2.0 + Math.min(sentenceTerms.size(), 30) * 0.025
                        + Math.max(0, 1.6 - order * 0.035) + 1.0 / (index + 1);
                candidates.add(new ScoredSentence(sentence, index, score));
            }
        }
        Set<String> seen = new HashSet<>();
        Map<Integer, Integer> perSource = new HashMap<>();
        List<ScoredSentence> selected = candidates.stream().sorted(Comparator.comparingDouble(ScoredSentence::score).reversed())
                .filter(s -> seen.add(s.text().toLowerCase(Locale.ROOT)))
                .filter(s -> perSource.merge(s.sourceIndex(), 1, Integer::sum) <= 2).limit(4).toList();
        if (selected.isEmpty()) {
            return "I found these pages, but they did not contain enough readable text to make a reliable summary. Open the source links to review them directly.";
        }
        return "Here’s a concise overview based on the sources I found:\n\n" + selected.stream()
                .map(s -> "- " + s.text() + " [" + (s.sourceIndex() + 1) + "]")
                .collect(Collectors.joining("\n\n"));
    }

    public static String summarizeText(String text, String instruction) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        int count = requestedCount(instruction);
        List<ScoredSentence> sentences = new ArrayList<>();
        String[] parts = SENTENCE_SPLIT.split(normalized);
        Map<String, Integer> frequency = new HashMap<>();
        for (String part : parts) for (String term : terms(part)) frequency.merge(term, 1, Integer::sum);
        for (int i = 0; i < parts.length; i++) {
            String sentence = parts[i].trim();
            if (sentence.isBlank()) continue;
            double score = terms(sentence).stream().mapToInt(t -> frequency.getOrDefault(t, 0)).sum()
                    + (i == 0 ? 1.0 : 0) + (i == parts.length - 1 ? 0.15 : 0);
            sentences.add(new ScoredSentence(sentence, i, score));
        }
        if (sentences.size() <= count) return sentences.stream().map(s -> "- " + s.text()).collect(Collectors.joining("\n\n"));
        Set<String> seen = new HashSet<>();
        List<ScoredSentence> chosen = sentences.stream().sorted(Comparator.comparingDouble(ScoredSentence::score).reversed())
                .filter(s -> seen.add(s.text().toLowerCase(Locale.ROOT))).limit(count)
                .sorted(Comparator.comparingInt(ScoredSentence::sourceIndex)).toList();
        return chosen.stream().map(s -> "- " + s.text()).collect(Collectors.joining("\n\n"));
    }

    private static int requestedCount(String instruction) {
        if (instruction != null) {
            Matcher matcher = Pattern.compile("\\b(\\d{1,2})\\s*(?:bullet|point|sentence)s?\\b", Pattern.CASE_INSENSITIVE).matcher(instruction);
            if (matcher.find()) return Math.clamp(Integer.parseInt(matcher.group(1)), 1, 10);
        }
        return 5;
    }

    private static Set<String> terms(String text) {
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> words = new HashSet<>();
        while (matcher.find()) if (!STOP_WORDS.contains(matcher.group())) words.add(matcher.group());
        return words;
    }

    private record ScoredSentence(String text, int sourceIndex, double score) { }
}
