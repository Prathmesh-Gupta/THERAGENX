package com.theragenx.theragenx_api.extraction.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.Deque;

/** Per-process conservative rolling token limiter. Actual Groq usage replaces each request reservation. */
@Component
public class GroqTokenRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(GroqTokenRateLimiter.class);
    private static final class Usage { final long time; int tokens; Usage(long time,int tokens){this.time=time;this.tokens=tokens;} }
    private final Deque<Usage> usage = new ArrayDeque<>();
    private final ThreadLocal<Usage> currentReservation = new ThreadLocal<>();
    private final int limit; private final double safety; private final double multiplier;
    private int reusableCachedPrefixTokens;
    public GroqTokenRateLimiter(@Value("${app.groq.rate-limit.input-tokens-per-minute:8000}") int limit, @Value("${app.groq.rate-limit.safety-ratio:0.70}") double safety, @Value("${app.groq.rate-limit.estimate-multiplier:2.2}") double multiplier) {
        this.limit=Math.max(1000,limit); this.safety=Math.min(.95,Math.max(.5,safety)); this.multiplier=Math.max(1.0,Math.min(4.0,multiplier));
    }
    public synchronized int estimateUncached(int staticPrefixTokens,int dynamicTokens) {
        int cached=Math.min(reusableCachedPrefixTokens,Math.max(0,staticPrefixTokens));
        int raw=Math.max(1,staticPrefixTokens-cached)+Math.max(1,dynamicTokens);
        return (int)Math.ceil(raw*multiplier);
    }
    public synchronized void observeCachedPrefix(int cachedTokens) {
        int actual=Math.max(0,cachedTokens); if(actual>reusableCachedPrefixTokens) reusableCachedPrefixTokens=actual;
        log.info("[GROQ CACHE] confirmedCachedPrefixTokens={}, reusableEstimateForNextRequests={}",actual,reusableCachedPrefixTokens);
    }
    public synchronized void awaitCapacity(int estimate) {
        int requested=Math.max(1,estimate), safeLimit=(int)(limit*safety);
        if(requested>safeLimit) throw new IllegalArgumentException("Estimated request usage ("+requested+") exceeds safe Groq rolling budget ("+safeLimit+"). Reduce chunk size or prompt size.");
        while(true) {
            prune(); int used=usage.stream().mapToInt(x->x.tokens).sum();
            if(used+requested<=safeLimit) { Usage reservation=new Usage(System.currentTimeMillis(),requested); usage.addLast(reservation); currentReservation.set(reservation); log.info("[GROQ RATE LIMIT] rollingUsedBeforeRequest={}, reservedTokens={}, safeRollingLimit={}, configuredTpm={}, safetyRatio={}, estimateMultiplier={}",used,requested,safeLimit,limit,safety,multiplier); return; }
            long wait=Math.max(250,60_000-(System.currentTimeMillis()-usage.peekFirst().time)+150); log.info("[GROQ RATE LIMIT] waitingMillis={}, rollingUsed={}, reservedTokens={}, safeRollingLimit={}",wait,used,requested,safeLimit);
            try { wait(wait); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while waiting for Groq token capacity",e); }
        }
    }
    public synchronized void reconcileEstimate(int estimate,int actualTokens) {
        Usage reservation=currentReservation.get(); currentReservation.remove(); if(reservation!=null) reservation.tokens=Math.max(0,actualTokens); prune(); int used=usage.stream().mapToInt(x->x.tokens).sum(); log.info("[GROQ RATE LIMIT] reconciledActualTokens={}, rollingUsedAfterResponse={}, configuredTpm={}",Math.max(0,actualTokens),used,limit); notifyAll();
    }
    private void prune() { long cutoff=System.currentTimeMillis()-60_000; while(!usage.isEmpty() && usage.peekFirst().time<cutoff) usage.removeFirst(); }
}
