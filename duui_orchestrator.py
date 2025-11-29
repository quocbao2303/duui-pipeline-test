#!/usr/bin/env python3
"""
DUUI Pipeline Orchestrator - Python REST Client
Chains Sentiment → HateCheck → FactChecking
"""

import requests
import json
import time
from typing import Dict, Any, List

# Component endpoints
SENTIMENT_URL = "http://localhost:9001"
HATECHECK_URL = "http://localhost:9002"
FACTCHECK_URL = "http://localhost:9003"

TEST_TEXT = """
I'm really disappointed with my city lately. The public transport system is absolutely terrible - it breaks down constantly and the app crashes multiple times a day. The parks are getting worse with poor maintenance, and the streets feel increasingly unsafe.

The real problem is all these immigrants flooding into our country and taking jobs from locals. They don't respect our culture and should go back where they came from. These refugees are ruining our neighbourhoods and driving up crime. We need to stop letting them in before it's too late.

The government is making terrible decisions about urban planning and clearly favours foreign businesses over local ones. The unemployment is rising because of the influx of cheap foreign labor. Our traditional values are being eroded by this multicultural agenda that nobody asked for. We're losing our identity.

I'm tired of seeing our once-great city turn into something unrecognisable. Something needs to be done about these problems before it gets worse. Local residents are being pushed out by outsiders who don't belong here.
"""

FACT_CHECK_PAIRS = [
    # Pair 1: High support - nearly identical
    {
        "claim": "Paris is capital of France",
        "fact": "Paris serves as the capital city of France",
        "claim_index": 0
    },
    # Pair 2: Partial support - related but different emphasis
    {
        "claim": "Water boils at 100C",
        "fact": "Water boils at exactly 100 degrees Celsius at sea level",
        "claim_index": 1
    },
    # Pair 3: Low support - short claim vs long fact
    {
        "claim": "The Moon orbits Earth",
        "fact": "The Moon orbits around the Earth in approximately 27.3 days",
        "claim_index": 2
    },
    # Pair 4: CONTRADICTION - claim is factually wrong
    {
        "claim": "Python programming language was invented in 1990",
        "fact": "Python was first released by Guido van Rossum in February 1991",
        "claim_index": 3
    },
    # Pair 5: High support - clear fact statement
    {
        "claim": "Shakespeare wrote Hamlet",
        "fact": "William Shakespeare authored the tragedy Hamlet in the early 1600s",
        "claim_index": 4
    },
    # Pair 6: DIRECT CONTRADICTION - opposite claims
    {
        "claim": "The Great Wall of China is visible from space with the naked eye",
        "fact": "The Great Wall of China is not visible from space with the naked eye due to its width and color",
        "claim_index": 5
    }
]


def check_component(name: str, url: str) -> bool:
    """Check if a component is alive."""
    try:
        resp = requests.get(f"{url}/v1/typesystem", timeout=5)
        if resp.status_code == 200:
            print(f"  ✓ {name}: {url}")
            return True
        else:
            print(f"  ✗ {name}: HTTP {resp.status_code}")
            return False
    except requests.exceptions.RequestException as e:
        print(f"  ✗ {name}: {e}")
        return False


def run_sentiment(text: str) -> Dict[str, Any]:
    """Run Sentiment analysis."""
    print("\n[1/3] Running Sentiment Analysis...")
    
    payload = {
        "selections": [
            {
                "selection": "text",
                "sentences": [
                    {
                        "text": text,
                        "begin": 0,
                        "end": len(text)
                    }
                ]
            }
        ],
        "lang": "en",
        "doc_len": len(text),
        "model_name": "cardiffnlp/twitter-xlm-roberta-base-sentiment",
        "batch_size": 32,
        "ignore_max_length_truncation_padding": False
    }
    
    try:
        resp = requests.post(f"{SENTIMENT_URL}/v1/process", json=payload, timeout=180)
        if resp.status_code == 200:
            print(f"  ✓ Sentiment analysis completed")
            return resp.json()
        else:
            print(f"  ✗ Sentiment failed: HTTP {resp.status_code}")
            return {}
    except Exception as e:
        print(f"  ✗ Sentiment error: {e}")
        return {}


def run_hatecheck(text: str) -> Dict[str, Any]:
    """Run Hate Speech Detection."""
    print("\n[2/3] Running Hate Speech Detection...")
    
    payload = {
        "selections": [
            {
                "selection": "text",
                "sentences": [
                    {
                        "text": text,
                        "begin": 0,
                        "end": len(text)
                    }
                ]
            }
        ],
        "lang": "en",
        "doc_len": len(text)
    }
    
    try:
        resp = requests.post(f"{HATECHECK_URL}/v1/process", json=payload, timeout=60)
        if resp.status_code == 200:
            print(f"  ✓ Hate speech detection completed")
            return resp.json()
        else:
            print(f"  ✗ HateCheck failed: HTTP {resp.status_code}")
            return {}
    except Exception as e:
        print(f"  ✗ HateCheck error: {e}")
        return {}


def run_factcheck(claim_fact_pairs: List[Dict]) -> Dict[str, Any]:
    """
    Run Fact Checking via DUUI wrapper.
    """
    print("\n[3/3] Running Fact Checking...")
    
    if not claim_fact_pairs:
        return {}
    
    combined_text_parts = []
    claims_all = []
    facts_all = []
    current_pos = 0
    
    for claim_idx, pair in enumerate(claim_fact_pairs):
        claim_text = pair["claim"]
        fact_text = pair["fact"]
        
        claim_prefix = f"Claim {claim_idx + 1}: "
        claim_start = current_pos + len(claim_prefix)
        claim_end = claim_start + len(claim_text)
        
        combined_text_parts.append(claim_prefix + claim_text)
        current_pos = claim_end
        
        fact_prefix = f" Fact {claim_idx + 1}: "
        fact_start = current_pos + len(fact_prefix)
        fact_end = fact_start + len(fact_text)
        
        combined_text_parts.append(fact_prefix + fact_text)
        current_pos = fact_end
        combined_text_parts.append("\n")
        current_pos += 1
        
        claim_dict = {
            "begin": claim_start,
            "end": claim_end,
            "text": claim_text,
            "facts": [{"begin": fact_start, "end": fact_end, "text": fact_text}]
        }
        claims_all.append(claim_dict)
        
        fact_dict = {
            "begin": fact_start,
            "end": fact_end,
            "text": fact_text,
            "claims": [{"begin": claim_start, "end": claim_end, "text": claim_text}]
        }
        facts_all.append(fact_dict)
    
    combined_text = "".join(combined_text_parts)
    
    payload = {
        "text": combined_text,
        "lang": "en",
        "claims_all": claims_all,
        "facts_all": facts_all
    }
    
    try:
        resp = requests.post(f"{FACTCHECK_URL}/v1/process", json=payload, timeout=300)
        if resp.status_code == 200:
            result = resp.json()
            print(f"  ✓ Fact checking completed")
            
            consistency = result.get('consistency', [])
            if not consistency or len(consistency) == 0:
                print(f"  ⚠ Component bug: empty results returned")
                return {
                    "consistency": generate_demo_scores(claim_fact_pairs),
                    "is_demo": True
                }
            return result
        else:
            print(f"  ✗ FactChecking failed: HTTP {resp.status_code}")
            return {}
    except requests.exceptions.Timeout:
        print(f"  ✗ FactCheck timed out (>300s)")
        print(f"  ⚠ Using demo scores")
        return {
            "consistency": generate_demo_scores(claim_fact_pairs),
            "is_demo": True,
            "timed_out": True
        }
    except Exception as e:
        print(f"  ✗ FactCheck error: {e}")
        return {}


def generate_demo_scores(pairs: List[Dict]) -> List[float]:
    """Generate reasonable demo scores based on semantic similarity."""
    scores = []
    for pair in pairs:
        claim = pair["claim"].lower()
        fact = pair["fact"].lower()
        
        claim_words = set(claim.split())
        fact_words = set(fact.split())
        
        if len(claim_words | fact_words) == 0:
            scores.append(0.0)
            continue
        
        overlap = len(claim_words & fact_words)
        union = len(claim_words | fact_words)
        jaccard = overlap / union
        
        final_score = min(jaccard * 1.2 + 0.1, 1.0)
        scores.append(final_score)
    
    return scores


def truncate_text(text: str, max_length: int = 150) -> str:
    """Truncate text for display."""
    if len(text) > max_length:
        return text[:max_length] + "..."
    return text


def display_results(
    sentiment_result: Dict,
    hatecheck_result: Dict,
    factcheck_result: Dict,
    claim_fact_pairs: List[Dict],
    test_text: str
):
    """Display pipeline results."""
    print("\n" + "="*80)
    print("PIPELINE RESULTS")
    print("="*80)
    
    # --- SENTIMENT ---
    print("\n--- SENTIMENT OUTPUT ---")
    print(f"\n  Input Text:")
    print(f"  {truncate_text(test_text, 200)}")
    print()
    
    if sentiment_result and sentiment_result.get('selections'):
        meta = sentiment_result.get('meta', {})
        print(f"  Model: {meta.get('modelName', 'N/A')}")
        print(f"  Version: {meta.get('version', 'N/A')}\n")
        
        for selection in sentiment_result.get('selections', []):
            for sentence in selection.get('sentences', []):
                pos = sentence.get('pos')
                neu = sentence.get('neu')
                neg = sentence.get('neg')
                if pos is not None:
                    print(f"  Sentiment Scores:")
                    print(f"    Positive:  {pos:.4f}")
                    print(f"    Neutral:   {neu:.4f}")
                    print(f"    Negative:  {neg:.4f}")
                    
                    # Interpret
                    max_score = max(pos, neu, neg)
                    if max_score == neg:
                        sentiment = "NEGATIVE"
                    elif max_score == pos:
                        sentiment = "POSITIVE"
                    else:
                        sentiment = "NEUTRAL"
                    print(f"    Overall:   {sentiment}")
    else:
        print("  (No output)")
    
    # --- HATE SPEECH ---
    print("\n--- HATE SPEECH OUTPUT ---")
    print(f"\n  Input Text:")
    print(f"  {truncate_text(test_text, 200)}")
    print()
    
    if hatecheck_result:
        meta = hatecheck_result.get('meta', {})
        print(f"  Model: {meta.get('modelName', 'N/A')}\n")
        
        hate_scores = hatecheck_result.get('hate', [])
        if hate_scores:
            for i, hate in enumerate(hate_scores):
                non_hate = hatecheck_result.get('non_hate', [0])[i] if i < len(hatecheck_result.get('non_hate', [])) else 0
                print(f"  Hate Speech Detection Scores:")
                print(f"    Hate score:     {hate:.4f}")
                print(f"    Non-hate score: {non_hate:.4f}")
                
                if hate > 0.5:
                    print(f"    Status: ⚠ Contains hate speech patterns")
                else:
                    print(f"    Status: ✓ No hate speech detected")
        else:
            print("  (No hate speech detected)")
    else:
        print("  (No output)")
    
    # --- FACT CHECKING ---
    print("\n--- FACT CHECKING OUTPUT ---")
    if factcheck_result:
        is_demo = factcheck_result.get('is_demo', False)
        timed_out = factcheck_result.get('timed_out', False)
        consistency = factcheck_result.get('consistency', [])
        
        if consistency and len(consistency) > 0:
            if is_demo:
                if timed_out:
                    print("  ⚠ Component timed out - using demo scores\n")
                else:
                    print("  ⚠ Component returned empty - using demo scores\n")
            else:
                print()
            
            print(f"  Fact-Checking Results ({len(consistency)} pairs):\n")
            
            for i in range(min(len(consistency), len(claim_fact_pairs))):
                pair = claim_fact_pairs[i]
                score = consistency[i]
                
                print(f"  Pair {i+1}:")
                print(f"    Claim: {pair['claim']}")
                print(f"    Fact:  {pair['fact']}")
                print(f"    Score: {score:.4f}")
                
                if score > 0.7:
                    status = "✓ SUPPORTED (claim aligns with fact)"
                elif score > 0.5:
                    status = "⚠ PARTIALLY SUPPORTED"
                elif score > 0.3:
                    status = "⚠ WEAKLY SUPPORTED"
                else:
                    status = "✗ CONTRADICTED (fact opposes claim)"
                print(f"    {status}\n")
        else:
            print("  (No results)")
    else:
        print("  (No output)")
    
    print("="*80)


def main():
    print("="*80)
    print("DUUI Pipeline Orchestrator (Python REST Client)")
    print("Sentiment → HateCheck → FactChecking")
    print("="*80)
    
    print("\nChecking component availability...")
    sentiment_ok = check_component("Sentiment", SENTIMENT_URL)
    hatecheck_ok = check_component("HateCheck", HATECHECK_URL)
    factcheck_ok = check_component("FactCheck", FACTCHECK_URL)
    
    if not (sentiment_ok and hatecheck_ok and factcheck_ok):
        print("\n❌ Some components are not available.")
        print("\nTo start all components:")
        print("  docker start duui-sentiment duui-hatecheck duui-factchecking")
        return 1
    
    print("\n✅ All components available!")
    print(f"\nProcessing test document ({len(TEST_TEXT)} chars)...")
    print(f"Testing {len(FACT_CHECK_PAIRS)} claim-fact pairs...\n")
    
    start_time = time.time()
    
    sentiment_result = run_sentiment(TEST_TEXT)
    hatecheck_result = run_hatecheck(TEST_TEXT)
    factcheck_result = run_factcheck(FACT_CHECK_PAIRS)
    
    elapsed = time.time() - start_time
    
    display_results(sentiment_result, hatecheck_result, factcheck_result, FACT_CHECK_PAIRS, TEST_TEXT)
    
    print(f"✅ Pipeline completed in {elapsed:.2f} seconds")
    print(f"   ({elapsed/60:.1f} minutes)\n")
    return 0


if __name__ == "__main__":
    exit(main())