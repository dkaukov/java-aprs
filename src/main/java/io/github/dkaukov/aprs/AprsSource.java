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
 * Copyright (C) 2024 Vance Vagell
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

package io.github.dkaukov.aprs;

/** Transport direction/source values stored with physical APRS packet records. */
public final class AprsSource {
    public static final String UNKNOWN = "UNKNOWN";
    public static final String RX_RF = "RX_RF";
    public static final String TX_RF = "TX_RF";
    public static final String RX_APRS_IS = "RX_APRS_IS";
    public static final String TX_APRS_IS = "TX_APRS_IS";

    private AprsSource() {
    }
}
