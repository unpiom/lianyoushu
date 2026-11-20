package com.lianji.fitlog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        Map<Long, Session> sessions = new TreeMap<>();
        for (Plan plan : plans) {
            if (plan.repeatsOn(date)) {
                sessions.put(plan.id, new Session(date, plan, false));
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

    enum ForecastStatus { WAITING, REACHED, NO_DECLINE, NEEDS_UPDATE, OUT_OF_RANGE, ESTIMATED }

    static final class Forecast {
        final ForecastStatus status;
        final int records;
        final long span;
        final double kgPerWeek;
        final LocalDate date;

        Forecast(ForecastStatus status, int records, long span, double kgPerWeek, LocalDate date) {
            this.status = status;
            this.records = records;
            this.span = span;
            this.kgPerWeek = kgPerWeek;
            this.date = date;
        }
    }

    static Forecast forecast(List<Weight> weights, LocalDate today, double target) {
        List<Weight> history = new ArrayList<>();
        for (Weight weight : weights) {
            if (!weight.date.isAfter(today)) history.add(weight);
        }
        history.sort(Comparator.comparing(weight -> weight.date));
        if (history.isEmpty()) return new Forecast(ForecastStatus.WAITING, 0, 0, 0, null);

        Weight latest = history.get(history.size() - 1);
        LocalDate recent = today.minusDays(29);
        int first = history.size() - 1;
        // Prefer the last 30 days. Extend backwards only to meet the data requirement.
        while (first > 0) {
            Weight oldestIncluded = history.get(first);
            boolean enough = history.size() - first >= 4
                    && ChronoUnit.DAYS.between(oldestIncluded.date, latest.date) >= 7;
            if (enough && history.get(first - 1).date.isBefore(recent)) break;
            first--;
        }
        List<Weight> sample = history.subList(first, history.size());
        LocalDate start = sample.get(0).date;
        int count = sample.size();
        long span = ChronoUnit.DAYS.between(start, latest.date);
        if (count < 4 || span < 7) return new Forecast(ForecastStatus.WAITING, count, span, 0, null);
        if (latest.value <= target) return new Forecast(ForecastStatus.REACHED, count, span, 0, latest.date);

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
        if (slope >= -1e-9) return new Forecast(ForecastStatus.NO_DECLINE, count, span, weekly, null);

        double days = Math.max(1, Math.ceil((latest.value - target) / -slope - 1e-9));
        if (days > ChronoUnit.DAYS.between(latest.date, LocalDate.of(9999, 12, 31))) {
            return new Forecast(ForecastStatus.OUT_OF_RANGE, count, span, weekly, null);
        }
        LocalDate estimated = latest.date.plusDays((long) days);
        if (estimated.isBefore(today)) {
            return new Forecast(ForecastStatus.NEEDS_UPDATE, count, span, weekly, null);
        }
        return new Forecast(ForecastStatus.ESTIMATED, count, span, weekly, estimated);
    }

    private Journal() { }
}
