package com.lianji.fitlog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

final class Journal {
    static class Measurement {
        final LocalDate date;
        final double value;

        Measurement(LocalDate date, double value) {
            this.date = date;
            this.value = value;
        }
    }

    static final class Weight extends Measurement {
        final boolean locked;

        Weight(LocalDate date, double kg, boolean locked) {
            super(date, kg);
            this.locked = locked;
        }
    }

    static final class Exercise {
        final String name;
        final int sets;

        Exercise(String name, int sets) {
            this.name = name;
            this.sets = sets;
        }
    }

    static final class Plan {
        final long id;
        final String name;
        final int days;
        final int minutes;
        final List<Exercise> exercises;

        Plan(long id, String name, int days, int minutes, List<Exercise> exercises) {
            this.id = id;
            this.name = name;
            this.days = days;
            this.minutes = minutes;
            this.exercises = exercises;
        }

        boolean repeatsOn(LocalDate date) {
            return (days & dayBit(date.getDayOfWeek())) != 0;
        }
    }

    static final class Session {
        final LocalDate date;
        final Plan plan;
        final boolean done;
        final Long durationSeconds;
        final List<SetLog> sets;

        Session(LocalDate date, Plan plan, boolean done) {
            this(date, plan, done, null, new ArrayList<>());
        }

        Session(LocalDate date, Plan plan, boolean done, Long durationSeconds, List<SetLog> sets) {
            this.date = date;
            this.plan = plan;
            this.done = done;
            this.durationSeconds = durationSeconds;
            this.sets = sets;
        }
    }

    static final class SetLog {
        final int exerciseIndex;
        final Integer reps;
        final Double loadKg;

        SetLog(int exerciseIndex, Integer reps, Double loadKg) {
            this.exerciseIndex = exerciseIndex;
            this.reps = reps;
            this.loadKg = loadKg;
        }
    }

    static final class WorkoutDraft {
        static final int DEFAULT_REST_SECONDS = 60;

        final LocalDate date;
        final Plan plan;
        final long startedAt;
        final int restSeconds;
        final long restUntil;
        final List<SetLog> sets;

        WorkoutDraft(LocalDate date, Plan plan, long startedAt, int restSeconds, long restUntil, List<SetLog> sets) {
            this.date = date;
            this.plan = plan;
            this.startedAt = startedAt;
            this.restSeconds = restSeconds;
            this.restUntil = restUntil;
            this.sets = sets;
        }

        WorkoutDraft withSet(SetLog set, long now) {
            List<SetLog> updated = new ArrayList<>(sets);
            updated.add(set);
            return new WorkoutDraft(date, plan, startedAt, restSeconds, now + restSeconds * 1000L, updated);
        }

        WorkoutDraft undoSet() {
            List<SetLog> updated = new ArrayList<>(sets);
            if (!updated.isEmpty()) updated.remove(updated.size() - 1);
            return new WorkoutDraft(date, plan, startedAt, restSeconds, 0, updated);
        }

        WorkoutDraft withRest(int seconds, long until) {
            return new WorkoutDraft(date, plan, startedAt, seconds, until, sets);
        }

        long elapsedSeconds(long now) {
            return Math.max(0, (now - startedAt) / 1000);
        }

        int completedSets(int exerciseIndex) {
            int count = 0;
            for (SetLog set : sets) {
                if (set.exerciseIndex == exerciseIndex) count++;
            }
            return count;
        }

        int plannedSets() {
            int count = 0;
            for (Exercise exercise : plan.exercises) count += exercise.sets;
            return count;
        }
    }

    static final class Week {
        final LocalDate start;
        final List<Session> sessions = new ArrayList<>();

        Week(LocalDate today, List<Plan> plans, List<Session> checkIns) {
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (int offset = 0; offset < 7; offset++) {
                sessions.addAll(sessionsOn(start.plusDays(offset), plans, checkIns));
            }
        }

        List<Session> on(LocalDate date) {
            List<Session> result = new ArrayList<>();
            for (Session session : sessions) {
                if (session.date.equals(date)) result.add(session);
            }
            return result;
        }

        int completed() {
            int count = 0;
            for (Session session : sessions) {
                if (session.done) count++;
            }
            return count;
        }
    }

    static List<Session> sessionsOn(LocalDate date, List<Plan> plans, List<Session> checkIns) {
        return sessionsOn(date, plans, checkIns, Collections.emptyList());
    }

    static List<Session> sessionsOn(LocalDate date, List<Plan> plans, List<Session> checkIns, List<WorkoutDraft> drafts) {
        Map<Long, Session> sessions = new TreeMap<>();
        for (Plan plan : plans) {
            if (plan.repeatsOn(date)) {
                sessions.put(plan.id, new Session(date, plan, false));
            }
        }
        for (WorkoutDraft draft : drafts) {
            if (draft.date.equals(date)) {
                sessions.put(draft.plan.id, new Session(date, draft.plan, false));
            }
        }
        for (Session checkIn : checkIns) {
            if (checkIn.date.equals(date)) {
                sessions.put(checkIn.plan.id, checkIn);
            }
        }
        return new ArrayList<>(sessions.values());
    }

    static int dayBit(DayOfWeek weekday) {
        return 1 << (weekday.getValue() - 1);
    }

    static <T extends Measurement> List<T> between(List<T> readings, LocalDate first, LocalDate last) {
        List<T> result = new ArrayList<>();
        for (T reading : readings) {
            if (!reading.date.isBefore(first) && !reading.date.isAfter(last)) result.add(reading);
        }
        return result;
    }

    static double average(List<Weight> weights) {
        double sum = 0;
        for (Weight weight : weights) sum += weight.value;
        return weights.isEmpty() ? Double.NaN : sum / weights.size();
    }

    enum ForecastStatus { WAITING, REACHED, NEEDS_UPDATE, NO_VARIATION, ESTIMATED }

    static final int FORECAST_WINDOW_DAYS = 30;
    static final int MIN_FORECAST_RECORDS = 6;
    static final int MIN_FORECAST_SPAN_DAYS = 5;
    static final int MAX_LAST_RECORD_AGE_DAYS = 7;
    static final int MAX_FORECAST_DAYS = 56;
    private static final int FORECAST_DRAWS = 4096;

    static final class Forecast {
        final ForecastStatus status;
        final int records;
        final long span;
        final double kgPerWeek;
        final LocalDate date;
        final double probability;
        final int maxDays;
        final LocalDate deadline;

        Forecast(ForecastStatus status, int records, long span, double kgPerWeek, LocalDate date,
                 int maxDays, LocalDate deadline) {
            this(status, records, span, kgPerWeek, date, Double.NaN, maxDays, deadline);
        }

        Forecast(ForecastStatus status, int records, long span, double kgPerWeek, LocalDate date,
                 double probability, int maxDays, LocalDate deadline) {
            this.status = status;
            this.records = records;
            this.span = span;
            this.kgPerWeek = kgPerWeek;
            this.date = date;
            this.probability = probability;
            this.maxDays = maxDays;
            this.deadline = deadline;
        }
    }

    static Forecast forecast(List<Weight> weights, LocalDate today, double target, LocalDate requestedDeadline) {
        long requestedDays = ChronoUnit.DAYS.between(today, requestedDeadline);
        if (requestedDays < 0 || requestedDays > MAX_FORECAST_DAYS) {
            throw new IllegalArgumentException("Deadline outside forecast horizon");
        }
        List<Weight> sample = between(weights, today.minusDays(FORECAST_WINDOW_DAYS - 1), today);
        sample.sort(Comparator.comparing(weight -> weight.date));
        int count = sample.size();
        LocalDate start = count == 0 ? today : sample.get(0).date;
        LocalDate lastDay = count == 0 ? today : sample.get(count - 1).date;
        long span = ChronoUnit.DAYS.between(start, lastDay);
        int maxDays = forecastLimit(span);
        int deadlineDay = (int) Math.min(requestedDays, maxDays);
        LocalDate deadline = today.plusDays(deadlineDay);
        if (count > 0 && sample.get(count - 1).value <= target) {
            return new Forecast(ForecastStatus.REACHED, count, span, 0, lastDay, maxDays, deadline);
        }
        if (count < MIN_FORECAST_RECORDS || span < MIN_FORECAST_SPAN_DAYS) {
            return new Forecast(ForecastStatus.WAITING, count, span, 0, null, maxDays, deadline);
        }
        if (lastDay.isBefore(today.minusDays(MAX_LAST_RECORD_AGE_DAYS))) {
            return new Forecast(ForecastStatus.NEEDS_UPDATE, count, span, 0, null, maxDays, deadline);
        }

        double meanDay = 0;
        for (Weight weight : sample) meanDay += ChronoUnit.DAYS.between(start, weight.date);
        meanDay /= count;
        double meanWeight = average(sample);
        double variance = 0;
        double covariance = 0;
        for (Weight weight : sample) {
            double dayDelta = ChronoUnit.DAYS.between(start, weight.date) - meanDay;
            variance += dayDelta * dayDelta;
            covariance += dayDelta * (weight.value - meanWeight);
        }
        double slope = covariance / variance;
        double weekly = slope * 7;
        double squaredError = 0;
        for (Weight weight : sample) {
            double day = ChronoUnit.DAYS.between(start, weight.date) - meanDay;
            double error = weight.value - (meanWeight + slope * day);
            squaredError += error * error;
        }
        if (squaredError <= 1e-12) {
            return new Forecast(ForecastStatus.NO_VARIATION, count, span, weekly, null, maxDays, deadline);
        }
        double todayOffset = ChronoUnit.DAYS.between(start, today) - meanDay;
        int[] crossings = crossingDays(count, squaredError, variance, meanWeight - target, slope, todayOffset, maxDays);
        int reached = 0;
        double probability = 0;
        LocalDate estimated = null;
        for (int day = 0; day < crossings.length; day++) {
            reached += crossings[day];
            if (day == deadlineDay) probability = (double) reached / FORECAST_DRAWS;
            if (estimated == null && reached * 2 >= FORECAST_DRAWS) estimated = today.plusDays(day);
        }
        return new Forecast(ForecastStatus.ESTIMATED, count, span, weekly, estimated, probability, maxDays, deadline);
    }

    private static int forecastLimit(long span) {
        if (span >= 14) return MAX_FORECAST_DAYS;
        if (span >= 7) return 14;
        return 7;
    }

    private static int[] crossingDays(int count, double squaredError, double daySumOfSquares,
                                      double meanGap, double slope, double todayOffset, int maxDays) {
        // Sample the fitted line's joint posterior under a Gaussian residual model with unknown variance.
        // Centering the days makes the intercept and slope independent conditional on that variance.
        Random random = new Random(0L);
        double meanScale = 1.0 / Math.sqrt(count);
        double slopeScale = 1.0 / Math.sqrt(daySumOfSquares);
        int[] crossings = new int[maxDays + 1];
        for (int draw = 0; draw < FORECAST_DRAWS; draw++) {
            double chiSquare = 0;
            for (int degree = 0; degree < count - 2; degree++) {
                double normal = random.nextGaussian();
                chiSquare += normal * normal;
            }
            double residualScale = Math.sqrt(squaredError / chiSquare);
            double sampledGap = meanGap + residualScale * meanScale * random.nextGaussian();
            double sampledSlope = slope + residualScale * slopeScale * random.nextGaussian();
            double gapToday = sampledGap + sampledSlope * todayOffset;
            if (gapToday <= 0) {
                crossings[0]++;
            } else if (sampledSlope < 0) {
                double day = Math.ceil(gapToday / -sampledSlope);
                if (day <= maxDays) crossings[(int) day]++;
            }
        }
        return crossings;
    }

    private Journal() { }
}
