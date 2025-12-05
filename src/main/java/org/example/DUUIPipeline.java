package org.example;

import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.jcas.JCas;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIRemoteDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIDockerDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.DUUILuaContext;

/**
 * DUUI Pipeline Demo: Sentiment → HateCheck → FactChecking
 * 
 * This demonstrates the DUUI-native approach to chaining NLP components.
 * Each component runs as a Docker container exposing REST endpoints.
 */
public class DUUIPipeline {

    // ==== TEST TEXT (DO NOT MODIFY) ====
    private static final String TEST_TEXT = """
        I really enjoy living in my city, because the parks are clean and people 
        are usually friendly on the streets. However, the new public transport app 
        is incredibly frustrating, it crashes almost every day and often shows the 
        wrong departure times.

        Some users on social media keep posting comments like "immigrants are ruining 
        our country and should all go home", which is a harmful stereotype and 
        completely ignores how much many newcomers contribute to the local economy 
        and culture. I strongly disagree with that kind of message and I think 
        platforms should react more quickly when such content spreads.

        There are also a lot of factual claims going around. One viral post says 
        that the city has more than ten million inhabitants, even though official 
        statistics put the population far below that. Another blog article claimed 
        that our metro system first opened in 1895, but the transport authority's 
        own website lists a much more recent opening date. Finally, a local news 
        site recently reported that unemployment in the region has dropped for 
        three years in a row, which sounds positive but should still be checked 
        carefully.
        """;

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("DUUI Pipeline: Sentiment → HateCheck → FactChecking");
        System.out.println("=".repeat(60));
        System.out.println();

        try {
            runPipeline();
        } catch (Exception e) {
            System.err.println("Pipeline failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runPipeline() throws Exception {
        // ============================================================
        // STEP 1: Initialize the DUUI Composer
        // ============================================================
        // The composer orchestrates the pipeline. It manages component
        // execution order and CAS data flow between components.
        
        System.out.println("[1/5] Initializing DUUIComposer...");
        
        DUUIComposer composer = new DUUIComposer()
            .withSkipVerification(true)     // Skip SSL verification for local
            .withDebugLevel(DUUIComposer.DebugLevel.DEBUG)
            .withLuaContext(new DUUILuaContext().withJsonLibrary());

        // ============================================================
        // STEP 2: Add Docker Driver
        // ============================================================
        // The Docker driver manages Docker containers. Alternatively,
        // use DUUIRemoteDriver for already-running services.
        
        System.out.println("[2/5] Adding Docker driver...");
        
        DUUIDockerDriver dockerDriver = new DUUIDockerDriver();
        composer.addDriver(dockerDriver);

        // Also add remote driver for pre-started containers
        DUUIRemoteDriver remoteDriver = new DUUIRemoteDriver();
        composer.addDriver(remoteDriver);

        // ============================================================
        // STEP 3: Add Components in Order
        // ============================================================
        // Components are added in execution order:
        //   1. Sentiment - analyzes emotional tone
        //   2. HateCheck - detects hate speech patterns  
        //   3. FactChecking - identifies claims for verification
        //
        // Each component adds annotations to the CAS.
        
        System.out.println("[3/5] Adding pipeline components...");
        
        // --- Component 1: Sentiment Analysis ---
        // This component analyzes the sentiment of text spans
        // Annotations added: Sentiment (with polarity scores)
        System.out.println("  → Adding Sentiment component (port 9001)");
        composer.add(
            new DUUIRemoteDriver.Component("http://localhost:9001")
                .withScale(1)  // Number of parallel instances
        );

        // --- Component 2: HateCheck ---
        // This component detects hate speech patterns
        // Annotations added: HateSpeech (with category, confidence)
        System.out.println("  → Adding HateCheck component (port 9002)");
        composer.add(
            new DUUIRemoteDriver.Component("http://localhost:9002")
                .withScale(1)
        );

        // --- Component 3: FactChecking ---
        // This component identifies factual claims
        // Annotations added: FactClaim (with claim text, checkworthy score)
        System.out.println("  → Adding FactChecking component (port 9003)");
        composer.add(
            new DUUIRemoteDriver.Component("http://localhost:9003")
                .withScale(1)
        );

        // ============================================================
        // STEP 4: Create CAS and Add Document
        // ============================================================
        // JCas is the Java interface to the CAS data structure.
        // We set the document text and language.
        
        System.out.println("[4/5] Creating CAS with test document...");
        
        JCas jCas = JCasFactory.createJCas();
        jCas.setDocumentText(TEST_TEXT);
        jCas.setDocumentLanguage("en");

        System.out.println("  Document length: " + TEST_TEXT.length() + " chars");
        System.out.println("  Document language: en");

        // ============================================================
        // STEP 5: Run the Pipeline
        // ============================================================
        // The composer sends the CAS through each component in order.
        // Each component adds its annotations to the CAS.
        
        System.out.println("[5/5] Running pipeline...");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        composer.run(jCas, "Pipeline Run");
        long endTime = System.currentTimeMillis();

        System.out.println();
        System.out.println("Pipeline completed in " + (endTime - startTime) + "ms");
        System.out.println();

        // ============================================================
        // OUTPUT: Display Results
        // ============================================================
        displayResults(jCas);

        // Cleanup
        composer.shutdown();
    }

    private static void displayResults(JCas jCas) {
        System.out.println("=".repeat(60));
        System.out.println("PIPELINE RESULTS");
        System.out.println("=".repeat(60));
        System.out.println();

        // Get all annotations and display them
        // NOTE: The actual annotation types depend on the components
        // This is a generic way to inspect what annotations were added
        
        System.out.println("--- All Annotations in CAS ---");
        jCas.getAnnotationIndex().forEach(annotation -> {
            String typeName = annotation.getType().getShortName();
            int begin = annotation.getBegin();
            int end = annotation.getEnd();
            String coveredText = annotation.getCoveredText();
            
            // Truncate covered text for display
            if (coveredText.length() > 50) {
                coveredText = coveredText.substring(0, 47) + "...";
            }
            coveredText = coveredText.replace("\n", " ");
            
            System.out.printf("  [%s] (%d-%d): %s%n", 
                typeName, begin, end, coveredText);
        });

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("END OF RESULTS");
        System.out.println("=".repeat(60));
    }
}
