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
 * AVRS - http://avrs.sourceforge.net/
 *
 * Copyright (C) 2011 John Gorkos, AB0OO
 *
 * AVRS is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * AVRS is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AVRS; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA
 */
package io.github.dkaukov.aprs.parser;

import java.util.ArrayList;

/**
 * 
 * @author johng
 * This class represents a single digipeater in a TNC2-format VIA string.
 * 
 */
public class Digipeater extends Callsign {
    private Digipeater(Digipeater source) {
        super(source.callsign);
        ssid = source.ssid;
        used = source.used;
    }

    public final Digipeater copy() {
        return new Digipeater(this);
    }

	private static final long serialVersionUID = 1L;
    private boolean used;
    
    public Digipeater(String call) {
	super(call.replaceAll("\\*", ""));
        if ( call.indexOf("*") >= 0 ) {
		setUsed(true);
	}
    }
    public Digipeater(byte[] data, int offset) {
	    super(data, offset);
	    this.used = (data[offset + 6] & 0x80) == 0x80;
    }

    /** parse a comma-separated list of digipeaters
     * @return the list of digipeaters as an array
     */
    public static ArrayList<Digipeater> parseList(String digiList, boolean includeFirst) {
	String[] digiTemp = digiList.split(",");
	ArrayList<Digipeater> digis = new ArrayList<Digipeater>();
	boolean includeNext = includeFirst;
	// for now, '*' is set for all digis with used bit.
	// however, only the last used digi should have a '*'
	for (String digi : digiTemp) {
		String digiTrim = digi.trim();
		if (digiTrim.length() > 0 && includeNext)
			digis.add(new Digipeater(digiTrim));
		includeNext = true;
	}
	return digis;
    }

    /**
     * @return the used
     */
    public boolean isUsed() {
        return used;
    }

    /**
     * @param used the used to set
     */
    public void setUsed(boolean used) {
        this.used = used;
    }
    
    
    /** 
     * @return String
     */
    @Override
    public String toString() {
        return super.toString() + ( isUsed() ? "*":"");
    }

    
    /** 
     * @return byte[]
     * @throws IllegalArgumentException
     */
    @Override
    public byte[] toAX25() throws IllegalArgumentException {
        byte[] ax25 = super.toAX25();
	ax25[6] |= (isUsed()?0x80:0);
	return ax25;
    }
}
