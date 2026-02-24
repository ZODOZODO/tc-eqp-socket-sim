package com.nori.tc.eqpsim.socket.scenario.runtime;

import com.nori.tc.eqpsim.socket.scenario.FaultStep;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 채널 단위 fault 상태.
 *
 * - duration 기반: expiresAtMs
 * - next 기반: remainingCount
 */
public final class FaultState {

    public static final class DurationPolicy {
        public final long expiresAtMs;

        public DurationPolicy(long expiresAtMs) {
            this.expiresAtMs = expiresAtMs;
        }

        public boolean active(long nowMs) {
            return nowMs < expiresAtMs;
        }
    }

    public static final class NextPolicy {
        public final AtomicInteger remaining;

        public NextPolicy(int remaining) {
            this.remaining = new AtomicInteger(remaining);
        }

        public boolean tryConsumeOne() {
            int v;
            do {
                v = remaining.get();
                if (v <= 0) return false;
            } while (!remaining.compareAndSet(v, v - 1));
            return true;
        }

        public boolean active() {
            return remaining.get() > 0;
        }
    }

    public static final class Delay {
        public final long delayMs;
        public final long jitterMs;
        public final FaultStep.ScopeMode mode;
        public final DurationPolicy duration;
        public final NextPolicy next;

        public Delay(long delayMs, long jitterMs, FaultStep.ScopeMode mode, DurationPolicy duration, NextPolicy next) {
            this.delayMs = delayMs;
            this.jitterMs = jitterMs;
            this.mode = mode;
            this.duration = duration;
            this.next = next;
        }
    }

    public static final class Fragment {
        public final int minParts;
        public final int maxParts;
        public final FaultStep.ScopeMode mode;
        public final DurationPolicy duration;
        public final NextPolicy next;

        public Fragment(int minParts, int maxParts, FaultStep.ScopeMode mode, DurationPolicy duration, NextPolicy next) {
            this.minParts = minParts;
            this.maxParts = maxParts;
            this.mode = mode;
            this.duration = duration;
            this.next = next;
        }
    }

    public static final class Drop {
        public final double rate;
        public final FaultStep.ScopeMode mode;
        public final DurationPolicy duration;
        public final NextPolicy next;

        public Drop(double rate, FaultStep.ScopeMode mode, DurationPolicy duration, NextPolicy next) {
            this.rate = rate;
            this.mode = mode;
            this.duration = duration;
            this.next = next;
        }
    }

    public static final class Corrupt {
        public final double rate;
        public final boolean protectFraming;
        public final FaultStep.ScopeMode mode;
        public final DurationPolicy duration;
        public final NextPolicy next;

        public Corrupt(double rate, boolean protectFraming, FaultStep.ScopeMode mode, DurationPolicy duration, NextPolicy next) {
            this.rate = rate;
            this.protectFraming = protectFraming;
            this.mode = mode;
            this.duration = duration;
            this.next = next;
        }
    }

    private volatile Delay delay;
    private volatile Fragment fragment;
    private volatile Drop drop;
    private volatile Corrupt corrupt;

    public Delay getDelay() { return delay; }
    public Fragment getFragment() { return fragment; }
    public Drop getDrop() { return drop; }
    public Corrupt getCorrupt() { return corrupt; }

    public void clearAll() {
        delay = null;
        fragment = null;
        drop = null;
        corrupt = null;
    }

    public void applyFault(FaultStep step) {
        long now = System.currentTimeMillis();

        DurationPolicy duration = null;
        NextPolicy next = null;

        if (step.getScopeMode() == FaultStep.ScopeMode.DURATION) {
            duration = new DurationPolicy(now + step.getDurationMs());
        } else {
            next = new NextPolicy(step.getNextCount());
        }

        switch (step.getType()) {
            case DELAY -> delay = new Delay(step.getDelayMs(), step.getJitterMs() == null ? 0 : step.getJitterMs(), step.getScopeMode(), duration, next);
            case FRAGMENT -> fragment = new Fragment(step.getMinParts(), step.getMaxParts(), step.getScopeMode(), duration, next);
            case DROP -> drop = new Drop(step.getRate(), step.getScopeMode(), duration, next);
            case CORRUPT -> corrupt = new Corrupt(step.getRate(), step.getProtectFraming() == null ? true : step.getProtectFraming(), step.getScopeMode(), duration, next);
            case CLEAR -> clearAll();
            case DISCONNECT -> {
                // disconnect는 runner에서 수행(스케줄 close)
            }
        }
    }

    public static boolean isActive(Delay d) {
        long now = System.currentTimeMillis();
        if (d == null) return false;
        return d.mode == FaultStep.ScopeMode.DURATION ? d.duration.active(now) : d.next.active();
    }

    public static boolean isActive(Fragment f) {
        long now = System.currentTimeMillis();
        if (f == null) return false;
        return f.mode == FaultStep.ScopeMode.DURATION ? f.duration.active(now) : f.next.active();
    }

    public static boolean isActive(Drop d) {
        long now = System.currentTimeMillis();
        if (d == null) return false;
        return d.mode == FaultStep.ScopeMode.DURATION ? d.duration.active(now) : d.next.active();
    }

    public static boolean isActive(Corrupt c) {
        long now = System.currentTimeMillis();
        if (c == null) return false;
        return c.mode == FaultStep.ScopeMode.DURATION ? c.duration.active(now) : c.next.active();
    }
}