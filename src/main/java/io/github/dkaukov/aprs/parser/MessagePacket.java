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

import java.nio.charset.StandardCharsets;

public final class MessagePacket extends InformationField {
    private MessagePacket(MessagePacket source) {
        super(source);
        messageBody = source.messageBody;
        messageNumber = source.messageNumber;
        targetCallsign = source.targetCallsign;
        isAck = source.isAck;
        isRej = source.isRej;
    }

    @Override public MessagePacket copy() {
        return new MessagePacket(this);
    }

	private static final long serialVersionUID = 1L;
    private String messageBody;
    private String messageNumber;
    private String targetCallsign ="";
    private boolean isAck = false;
    private boolean isRej = false;
    
    public MessagePacket( byte[] bodyBytes, String destCall ) {
        super(bodyBytes);
        String message = new String(bodyBytes, StandardCharsets.ISO_8859_1);
        if ( message.length() < 2) {
            this.hasFault = true;
            return;
        }
        int msgSpc = message.indexOf(':', 2);
        if ( msgSpc < 1 ) {
        	this.targetCallsign = "UNKNOWN";
            this.hasFault = true;
            return;
        } else {
        	targetCallsign = message.substring(1,msgSpc).trim().toUpperCase();
        }
        int msgNumberIdx = message.lastIndexOf('{');
        this.messageNumber="";
        if ( msgNumberIdx > msgSpc ) {
            this.messageNumber = message.substring(msgNumberIdx+1);
            messageBody = message.substring(msgSpc + 1,msgNumberIdx);
        } else {
            messageBody = message.substring(msgSpc + 1);
        }
        String lcMsg = messageBody.toLowerCase();
        if ( lcMsg.startsWith("ack") ) {
        	isAck = true;
        	this.messageNumber = messageBody.substring(3,messageBody.length());
		this.messageBody = messageBody.substring(0, 3);
        }
        if ( lcMsg.startsWith("rej") ) {
        	isRej = true;
        	this.messageNumber = messageBody.substring(3,messageBody.length());
		this.messageBody = messageBody.substring(0, 3);
        }
    }
    
    public MessagePacket(String targetCallsign, String messageBody, String messageNumber) {
    	this.messageBody = messageBody;
    	this.targetCallsign = targetCallsign;
    	this.messageNumber = messageNumber;
    	if ( messageBody.equals("ack") ) isAck = true;
    	if ( messageBody.equals("rej") ) isRej = true;
    	super.setDataTypeIdentifier(':');
    }

    /**
     * Generates an APRS message payload with an optional message ID.
     * @param recipient The callsign of the person you are messaging (e.g., "N1AA")
     * @param messageText The body of your message
     * @param messageId The message number (1-5 chars) for requesting an ACK. Pass null to omit.
     * @return The payload ready to be passed to APRSPacket
     */
    public static byte[] createMessagePayload(String recipient, String messageText, String messageId) {
        // 1. Pad the recipient callsign to exactly 9 characters (left-justified)
        String paddedRecipient = String.format("%-9s", recipient);

        // 2. Format the message ID suffix (if provided)
        String idSuffix = "";
        if (messageId != null && !messageId.trim().isEmpty()) {
            messageId = messageId.trim();
            // Enforce the spec's 5-character maximum for the ID
            if (messageId.length() > 5) {
                messageId = messageId.substring(0, 5);
            }
            idSuffix = "{" + messageId;
        }

        // 3. Enforce overall length limits (Standard APRS text + ID should be <= 67 chars)
        // We subtract the length of the ID suffix to ensure we leave room for it
        int maxTextLength = 67 - idSuffix.length();
        if (messageText.length() > maxTextLength) {
            messageText = messageText.substring(0, maxTextLength);
        }

        // 4. Construct the exact APRS payload string
        String payloadString = ":" + paddedRecipient + ":" + messageText + idSuffix;

        // 5. Return as ASCII bytes
        return payloadString.getBytes(StandardCharsets.US_ASCII);
    }
    
    /**
     * @return the messageBody
     */
    public String getMessageBody() {
        return this.messageBody;
    }

    /**
     * @param messageBody the messageBody to set
     */
    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }

    /**
     * @return the messageNumber
     */
    public String getMessageNumber() {
        return messageNumber;
    }

    /**
     * @param messageNumber the messageNumber to set
     */
    public void setMessageNumber(String messageNumber) {
        this.messageNumber = messageNumber;
    }

    /**
     * @return the targetCallsign
     */
    public String getTargetCallsign() {
        return targetCallsign;
    }

    /**
     * @param targetCallsign the targetCallsign to set
     */
    public void setTargetCallsign(String targetCallsign) {
        this.targetCallsign = targetCallsign;
    }

	/**
	 * @return the isAck
	 */
	public boolean isAck() {
		return isAck;
	}

	/**
	 * @param isAck the isAck to set
	 */
	public void setAck(boolean isAck) {
		this.isAck = isAck;
	}

	/**
	 * @return the isRej
	 */
	public boolean isRej() {
		return isRej;
	}

	/**
	 * @param isRej the isRej to set
	 */
	public void setRej(boolean isRej) {
		this.isRej = isRej;
	}

	
    /** 
     * @return String
     */
    @Override
	public String toString() {
		if (rawBytes != null)
			return new String(rawBytes, StandardCharsets.ISO_8859_1);
		if ( this.messageBody.equals("ack") || this.messageBody.equals("rej")) {
			return String.format(":%-9s:%s%s", this.targetCallsign, this.messageBody, this.messageNumber);
		} else if (messageNumber.length() > 0) {
			return String.format(":%-9s:%s{%s", this.targetCallsign, this.messageBody, this.messageNumber);
		} else {
			return String.format(":%-9s:%s", this.targetCallsign, this.messageBody);
		}
	}
}
