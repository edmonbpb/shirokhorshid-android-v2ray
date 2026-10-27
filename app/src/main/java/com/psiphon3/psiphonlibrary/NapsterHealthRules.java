package com.psiphon3.psiphonlibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure decision rules for the Napster link health check. */
public final class NapsterHealthRules {

    public enum Status { UNKNOWN, SUSPICIOUS, DEAD, HEALTHY, SEMI_HEALTHY }

    public static final class Input {
        public final boolean networkOk;
        public final int generalSuccesses;
        public final boolean telegramOk;
        public final Status previous;
        public final int failStreak;

        public Input(boolean networkOk, int generalSuccesses, boolean telegramOk,
                Status previous, int failStreak) {
            this.networkOk = networkOk;
            this.generalSuccesses = generalSuccesses;
            this.telegramOk = telegramOk;
            this.previous = previous == null ? Status.UNKNOWN : previous;
            this.failStreak = Math.max(0, failStreak);
        }
    }

    public static final class Result {
        public final Status status;
        public final int failStreak;

        public Result(Status status, int failStreak) {
            this.status = status;
            this.failStreak = failStreak;
        }
    }

    private NapsterHealthRules() {
    }

    public static Result evaluate(Input in) {
        if (in == null) {
            return new Result(Status.UNKNOWN, 0);
        }
        if (!in.networkOk) {
            return new Result(Status.UNKNOWN, in.failStreak);
        }
        if (in.generalSuccesses < 2) {
            int streak = in.failStreak + 1;
            if (streak >= 2) {
                return new Result(Status.DEAD, streak);
            }
            return new Result(Status.SUSPICIOUS, streak);
        }
        if (in.telegramOk) {
            return new Result(Status.HEALTHY, 0);
        }
        return new Result(Status.SEMI_HEALTHY, 0);
    }

    public static int median(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int size = copy.size();
        if (size % 2 == 1) {
            return copy.get(size / 2);
        }
        return (copy.get(size / 2 - 1) + copy.get(size / 2)) / 2;
    }
}
