/*
 * This file is licensed under the GNU General Public License v3.0.
 *
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

/*
 * kv4p HT (see http://kv4p.com)
 * Copyright (C) 2026 Vance Vagell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.dkaukov.aprs.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Station capabilities carried by the APRS {@code <} data type. */
public final class StationCapabilitiesField extends APRSData {

    private StationCapabilitiesField(StationCapabilitiesField source) {
        super(source);
        this.igate = source.igate;
        this.messageCount = source.messageCount;
        this.localStationCount = source.localStationCount;
        this.displayText = source.displayText;
    }

    @Override public StationCapabilitiesField copy() {
        return new StationCapabilitiesField(this);
    }

    private static final long serialVersionUID = 1L;

    private final boolean igate;
    private final Integer messageCount;
    private final Integer localStationCount;
    private final String displayText;

    public StationCapabilitiesField(byte[] rawBytes) {
        super(rawBytes);
        type = APRSTypes.T_STATCAPA;

        String rawText = rawBytes.length <= 1 ? "" : new String(
            rawBytes, 1, rawBytes.length - 1, StandardCharsets.ISO_8859_1).trim();
        boolean parsedIgate = false;
        Integer parsedMessageCount = null;
        Integer parsedLocalStationCount = null;
        List<String> unrecognized = new ArrayList<>();
        for (String rawToken : rawText.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            String upperToken = token.toUpperCase(Locale.ROOT);
            if ("IGATE".equals(upperToken)) {
                parsedIgate = true;
            } else if (upperToken.startsWith("MSG_CNT=")) {
                parsedMessageCount = parseCount(token.substring(token.indexOf('=') + 1));
                if (parsedMessageCount == null) {
                    unrecognized.add(token);
                }
            } else if (upperToken.startsWith("LOC_CNT=")) {
                parsedLocalStationCount = parseCount(token.substring(token.indexOf('=') + 1));
                if (parsedLocalStationCount == null) {
                    unrecognized.add(token);
                }
            } else {
                unrecognized.add(token);
            }
        }

        igate = parsedIgate;
        messageCount = parsedMessageCount;
        localStationCount = parsedLocalStationCount;
        displayText = buildDisplayText(rawText, unrecognized);
        comment = displayText;
        setLastCursorPosition(rawBytes.length);
    }

    private static Integer parseCount(String value) {
        try {
            int count = Integer.parseInt(value.trim());
            return count < 0 ? null : count;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String buildDisplayText(String rawText, List<String> unrecognized) {
        List<String> parts = new ArrayList<>();
        if (igate) {
            parts.add("IGate");
        }
        if (messageCount != null) {
            parts.add(messageCount + (messageCount == 1 ? " message" : " messages"));
        }
        if (localStationCount != null) {
            parts.add(localStationCount
                + (localStationCount == 1 ? " local station" : " local stations"));
        }
        parts.addAll(unrecognized);
        return parts.isEmpty() ? rawText : String.join(" · ", parts);
    }

    public boolean isIgate() {
        return igate;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public Integer getLocalStationCount() {
        return localStationCount;
    }

    public String getDisplayText() {
        return displayText;
    }

    /** Formats legacy capability text that does not include the APRS {@code <} data type. */
    public static String formatDisplayText(String capabilityText) {
        byte[] bytes = ("<" + (capabilityText == null ? "" : capabilityText))
            .getBytes(StandardCharsets.ISO_8859_1);
        return new StationCapabilitiesField(bytes).getDisplayText();
    }

    @Override public String toString() {
        return displayText;
    }

    @Override public boolean hasFault() {
        return false;
    }
}
