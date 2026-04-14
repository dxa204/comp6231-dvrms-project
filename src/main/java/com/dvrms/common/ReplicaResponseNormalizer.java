package com.dvrms.common;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReplicaResponseNormalizer {

    private static final Pattern VEHICLE_ID_PATTERN = Pattern.compile("(MTL|WPG|BNF)\\d{4}");

    private ReplicaResponseNormalizer() {
    }

    public static String normalize(String method, String rawResult) {
        String raw = rawResult == null ? "" : rawResult.trim();

        if ("listAvailableVehicle".equals(method) || "listAvailableVehicles".equals(method)) {
            return normalizeVehicleListing(raw);
        }
        if ("findVehicle".equals(method)) {
            return normalizeVehicleListing(raw);
        }
        if ("displayCurrentBudget".equals(method)) {
            return normalizeBudget(raw);
        }
        if ("displayReservations".equals(method)) {
            return normalizeReservations(raw);
        }
        if ("addVehicle".equals(method)) {
            return normalizeStatus(raw, "SUCCESS", "added", "updated");
        }
        if ("removeVehicle".equals(method)) {
            return normalizeStatus(raw, "SUCCESS", "removed");
        }
        if ("reserveVehicle".equals(method)) {
            return normalizeReservation(raw);
        }
        if ("updateReservation".equals(method)) {
            return normalizeUpdate(raw);
        }
        if ("cancelReservation".equals(method)) {
            return normalizeCancel(raw);
        }

        return raw;
    }

    private static String normalizeVehicleListing(String raw) {
        Set<String> ids = new TreeSet<>();
        Matcher matcher = VEHICLE_ID_PATTERN.matcher(raw);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        if (ids.isEmpty()) {
            return "EMPTY";
        }

        StringBuilder builder = new StringBuilder();
        for (String id : ids) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static String normalizeReservation(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("only reserve one vehicle outside your home office")) {
            return canonicalRemoteLimitMessage(raw);
        }
        if (lower.contains("waitlisted")) {
            return "WAITLISTED";
        }
        if (lower.contains("insufficient budget")
                || lower.contains("over budget")
                || lower.startsWith("error: over budget")) {
            return "INSUFFICIENT_BUDGET";
        }
        if (lower.contains("reserved") || lower.contains("success")) {
            return "SUCCESS";
        }
        if (lower.startsWith("unavailable")
                || lower.contains("overlap")
                || lower.contains("conflict")
                || lower.contains("not available")
                || lower.contains("not in stock")) {
            return "UNAVAILABLE";
        }
        if (lower.contains("timeout")) {
            return "TIMEOUT";
        }
        return "ERROR";
    }

    private static String normalizeUpdate(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("no current reservation or waitlist for this vehicle")) {
            return "ERROR: No current reservation or waitlist for this vehicle.";
        }
        if (lower.contains("vehicle not found")) {
            return "ERROR: No current reservation or waitlist for this vehicle.";
        }
        if (lower.contains("only reserve one vehicle outside your home office")) {
            return canonicalRemoteLimitMessage(raw);
        }
        if (lower.contains("updated") || lower.contains("success")) {
            return "SUCCESS";
        }
        if (lower.contains("waitlist")) {
            return "WAITLISTED";
        }
        if (lower.contains("conflict")
                || lower.contains("unavailable")
                || lower.contains("no reservation")) {
            return "UNAVAILABLE";
        }
        if (lower.contains("timeout")) {
            return "TIMEOUT";
        }
        return "ERROR";
    }

    private static String normalizeCancel(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("cancelled") || lower.contains("canceled") || lower.contains("success")) {
            return "SUCCESS";
        }
        if (lower.contains("timeout")) {
            return "TIMEOUT";
        }
        return "ERROR";
    }

    private static String normalizeBudget(String raw) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(raw);
        if (matcher.find()) {
            return "Current budget: " + matcher.group(1);
        }
        return raw;
    }

    private static String normalizeReservations(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || "No active reservations.".equalsIgnoreCase(trimmed)) {
            return "No active reservations.";
        }

        String[] lines = trimmed.split("\\R+");
        Set<String> normalized = new TreeSet<>();
        for (String line : lines) {
            String clean = line.trim().replaceAll("\\s+", " ");
            if (!clean.isEmpty()) {
                normalized.add(clean);
            }
        }

        if (normalized.isEmpty()) {
            return "No active reservations.";
        }

        StringBuilder builder = new StringBuilder();
        for (String line : normalized) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String normalizeStatus(String raw, String successValue, String... successTokens) {
        String lower = raw.toLowerCase();
        for (String token : successTokens) {
            if (lower.contains(token)) {
                return successValue;
            }
        }
        return "ERROR";
    }

    private static String canonicalRemoteLimitMessage(String raw) {
        Matcher matcher = Pattern.compile("\\(([A-Z]{3})\\)").matcher(raw);
        if (matcher.find()) {
            return "ERROR: You can only reserve ONE vehicle outside your home office (" + matcher.group(1) + ").";
        }
        return "ERROR: You can only reserve ONE vehicle outside your home office.";
    }
}
