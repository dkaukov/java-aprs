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

import java.io.Serializable;

/**
 * @author johng
 *
 */
public final class RangeExtension extends DataExtension implements Serializable {

    private RangeExtension(RangeExtension source) {
        this.range = source.range;
    }

    @Override public RangeExtension copy() {
        return new RangeExtension(this);
    }

	private static final long serialVersionUID = 1L;
	private int range;
	
	public RangeExtension( int range ) {
		this.setRange(range);
	}

	/**
	 * @param range the range to set
	 */
	public void setRange(int range) {
		this.range = range;
	}

	/**
	 * @return the range
	 */
	public int getRange() {
		return range;
	}
	
	/**
	 * @return Enum indicating the data type extention
	*/
	@Override
	public APRSExtensions getType() {
		return APRSExtensions.T_RADIORANGE;
	}

	/** 
	 * @return String
	 */
	@Override
	public String toSAEString() {
		return "Range of "+range+" miles";
	}

}
