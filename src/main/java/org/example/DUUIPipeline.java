package org.example;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import org.apache.uima.UIMAException;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIRemoteDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.DUUILuaContext;
import org.texttechnologylab.annotation.Claim;
import org.texttechnologylab.annotation.Fact;
import org.texttechnologylab.annotation.FactChecking;
import org.texttechnologylab.annotation.Hate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * DUUI Pipeline: Sentiment → HateCheck → FactChecking
 * Updated and should be working now
 */
public class DUUIPipeline {

    // Component endpoints (containers must be running)
    private static final String SENTIMENT_ENDPOINT = "http://localhost:9001";
    private static final String HATECHECK_ENDPOINT = "http://localhost:9002";
    private static final String FACTCHECK_ENDPOINT = "http://localhost:9003";

    // Test text
    private static final String TEST_TEXT = """
        I really enjoy living in my city, because the parks are clean and people are usually friendly on the streets. However, the new public transport app is incredibly frustrating, it crashes almost every day and often shows the wrong departure times.

        Some users on social media keep posting comments like "immigrants are ruining our country and should all go home", which is a harmful stereotype and completely ignores how much many newcomers contribute to the local economy and culture. I strongly disagree with that kind of message and I think platforms should react more quickly when such content spreads.

        There are also a lot of factual claims going around. One viral post says that the city has more than ten million inhabitants, even though official statistics put the population far below that. Another blog article claimed that our metro system first opened in 1895, but the transport authority's own website lists a much more recent opening date. Finally, a local news site recently reported that unemployment in the region has dropped for three years in a row, which sounds positive but should still be checked carefully.
        """;

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("DUUI Pipeline: Sentiment → HateCheck → FactChecking");
        System.out.println("=".repeat(70));
        System.out.println();

        try {
            runPipeline();
        } catch (Exception e) {
            System.err.println("\n[ERROR] Pipeline failed:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runPipeline() throws Exception {
        // ============================================================
        // STEP 1: Initialize DUUIComposer
        // ============================================================
        System.out.println("[1/7] Initializing DUUIComposer...");

        DUUIComposer composer = new DUUIComposer()
                .withSkipVerification(true)
                .withDebugLevel(DUUIComposer.DebugLevel.DEBUG)
                .withLuaContext(new DUUILuaContext().withJsonLibrary());

        // ============================================================
        // STEP 2: Add Remote Driver
        // ============================================================
        System.out.println("[2/7] Adding RemoteDriver for Docker components...");

        DUUIRemoteDriver remoteDriver = new DUUIRemoteDriver();
        composer.addDriver(remoteDriver);

        // ============================================================
        // STEP 3: Add Components in Order
        // ============================================================
        System.out.println("[3/7] Adding pipeline components...");

        // Component 1: Sentiment Analysis
        System.out.println("      → Sentiment (localhost:9001)");
        composer.add(
                new DUUIRemoteDriver.Component(SENTIMENT_ENDPOINT)
                        .withParameter("model_name", "cardiffnlp/twitter-xlm-roberta-base-sentiment")
                        .withParameter("selection", "text")
        );

        // Component 2: HateCheck
        System.out.println("      → HateCheck (localhost:9002)");
        composer.add(
                new DUUIRemoteDriver.Component(HATECHECK_ENDPOINT)
                        .withParameter("selection", "text")
        );

        // Component 3: FactChecking
        System.out.println("      → FactChecking (localhost:9003)");
        composer.add(
                new DUUIRemoteDriver.Component(FACTCHECK_ENDPOINT)
        );

        // ============================================================
        // STEP 4: Create JCas and Set Document
        // ============================================================
        System.out.println("[4/7] Creating CAS with test document...");

        JCas jCas = JCasFactory.createJCas();
        jCas.setDocumentText(TEST_TEXT);
        jCas.setDocumentLanguage("en");

        System.out.println("      Document length: " + TEST_TEXT.length() + " characters");

        // ============================================================
        // STEP 5: Add Claim and Fact Annotations for FactChecking
        // ============================================================
        System.out.println("[5/7] Adding Claim/Fact annotations for FactChecking...");

        addClaimsAndFacts(jCas);

        // ============================================================
        // STEP 6: Run the Pipeline
        // ============================================================
        System.out.println("[6/7] Running pipeline (this may take 30-60 seconds)...");
        System.out.println();

        long startTime = System.currentTimeMillis();
        composer.run(jCas, "DUUI-Pipeline-Run");
        long endTime = System.currentTimeMillis();

        System.out.println();
        System.out.println("      Pipeline completed in " + (endTime - startTime) + " ms");

        // ============================================================
        // STEP 7: Display Results
        // ============================================================
        System.out.println("[7/7] Extracting results...");
        System.out.println();

        displayResults(jCas);

        // Cleanup
        composer.shutdown();
        System.out.println("\n[DONE] Pipeline finished successfully.");
    }

    /**
     * Add Claim and Fact annotations to the CAS.
     * FactChecking component reads these and checks each Claim against its linked Facts.
     */
    private static void addClaimsAndFacts(JCas jCas) {
        String text = jCas.getDocumentText();

        // Define claims and their corresponding facts to verify
        String[][] claimsAndFacts = {
                {
                        "the city has more than ten million inhabitants",
                        "Official statistics show the city population is approximately 3.5 million.",
                        "ten million"
                },
                {
                        "our metro system first opened in 1895",
                        "The city metro system opened in 1976 according to transport authority records.",
                        "1895"
                },
                {
                        "unemployment in the region has dropped for three years in a row",
                        "Regional unemployment statistics show a decline in 2022 and 2023 but a slight increase in 2021.",
                        "unemployment"
                }
        };

        List<Claim> claimAnnotations = new ArrayList<>();
        List<Fact> factAnnotations = new ArrayList<>();

        for (String[] pair : claimsAndFacts) {
            String claimText = pair[0];
            String factText = pair[1];
            String hint = pair[2];

            // Find claim position in text
            int claimStart = text.indexOf(hint);
            if (claimStart == -1) continue;

            // Expand to sentence boundaries (simplified)
            int claimEnd = text.indexOf(".", claimStart);
            if (claimEnd == -1) claimEnd = claimStart + claimText.length();
            else claimEnd += 1;

            // Create Claim annotation
            Claim claim = new Claim(jCas, claimStart, claimEnd);
            claim.setValue(claimText);

            // Create Fact annotation
            Fact fact = new Fact(jCas, 0, 0);
            fact.setValue(factText);

            // Link Claim to Fact
            FSArray<Fact> factsArray = new FSArray<>(jCas, 1);
            factsArray.set(0, fact);
            claim.setFacts(factsArray);

            // Link Fact to Claim
            FSArray<Claim> claimsArray = new FSArray<>(jCas, 1);
            claimsArray.set(0, claim);
            fact.setClaims(claimsArray);

            // Add to indexes
            claim.addToIndexes();
            fact.addToIndexes();

            claimAnnotations.add(claim);
            factAnnotations.add(fact);

            System.out.println("      Added: Claim @ [" + claimStart + "-" + claimEnd + "]");
        }

        System.out.println("      Total: " + claimAnnotations.size() + " claim-fact pairs");
    }

    /**
     * Display all annotations produced by the pipeline.
     */
    private static void displayResults(JCas jCas) {
        System.out.println("=".repeat(70));
        System.out.println("PIPELINE RESULTS");
        System.out.println("=".repeat(70));

        // --- Sentiment Results ---
        System.out.println("\n--- SENTIMENT ANNOTATIONS ---");
        try {
            Class<?> sentimentClass = Class.forName(
                    "org.hucompute.textimager.uima.type.Sentiment");
            @SuppressWarnings("unchecked")
            Collection<?> sentiments = JCasUtil.select(jCas,
                    (Class<? extends org.apache.uima.jcas.tcas.Annotation>) sentimentClass);

            if (sentiments.isEmpty()) {
                System.out.println("  (No Sentiment annotations found)");
            } else {
                for (Object s : sentiments) {
                    org.apache.uima.jcas.tcas.Annotation ann =
                            (org.apache.uima.jcas.tcas.Annotation) s;
                    String covered = truncate(ann.getCoveredText(), 60);
                    System.out.printf("  [%d-%d] %s%n",
                            ann.getBegin(), ann.getEnd(), covered);

                    // Try to get sentiment score via reflection
                    try {
                        java.lang.reflect.Method getScore =
                                sentimentClass.getMethod("getSentiment");
                        Object score = getScore.invoke(s);
                        System.out.printf("         Score: %s%n", score);
                    } catch (Exception ignored) {
                        // Score method not available
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  (Sentiment type not in classpath)");
        }

        // --- Hate Speech Results ---
        System.out.println("\n--- HATE SPEECH ANNOTATIONS ---");
        Collection<Hate> hateAnnotations = JCasUtil.select(jCas, Hate.class);
        if (hateAnnotations.isEmpty()) {
            System.out.println("  (No Hate annotations found)");
        } else {
            for (Hate h : hateAnnotations) {
                String covered = truncate(h.getCoveredText(), 60);
                System.out.printf("  [%d-%d] %s%n", h.getBegin(), h.getEnd(), covered);
                System.out.printf("         Hate: %.4f | Non-Hate: %.4f%n",
                        h.getHate(), h.getNonHate());
            }
        }

        // --- FactChecking Results ---
        System.out.println("\n--- FACT CHECKING ANNOTATIONS ---");
        Collection<FactChecking> factChecks = JCasUtil.select(jCas, FactChecking.class);
        if (factChecks.isEmpty()) {
            System.out.println("  (No FactChecking annotations found)");
        } else {
            for (FactChecking fc : factChecks) {
                Claim claim = fc.getClaim();
                Fact fact = fc.getFact();
                System.out.printf("  Claim: \"%s\"%n", truncate(claim.getValue(), 50));
                System.out.printf("  Fact:  \"%s\"%n", truncate(fact.getValue(), 50));
                System.out.printf("  Consistency Score: %.4f%n", fc.getConsistency());
                System.out.println();
            }
        }

        // --- Summary ---
        System.out.println("=".repeat(70));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(70));
        System.out.println("  Total annotations in CAS:");

        int totalCount = 0;
        for (org.apache.uima.jcas.tcas.Annotation a : jCas.getAnnotationIndex()) {
            totalCount++;
        }
        System.out.println("    All annotations: " + totalCount);
        System.out.println("    Hate annotations: " + hateAnnotations.size());
        System.out.println("    FactChecking annotations: " + factChecks.size());
    }

    /**
     * Truncate a string to maxLen characters, adding "..." if truncated.
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "(null)";
        // Replace newlines with spaces for cleaner display
        text = text.replace("\n", " ").replace("\r", "");
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 3) + "...";
    }
}
