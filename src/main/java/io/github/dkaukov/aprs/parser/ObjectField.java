package io.github.dkaukov.aprs.parser;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

public final class ObjectField extends APRSData {

    private ObjectField(ObjectField source) {
        super(source);
        this.objectName = source.objectName;
        this.live = source.live;
        this.timestamp = source.timestamp == null ? null : source.timestamp.copy();
        this.position = source.position == null ? null : source.position.copy();
    }

    @Override public ObjectField copy() {
        return new ObjectField(this);
    }

	private static final long serialVersionUID = 1L;
	protected String objectName;
	protected boolean live = true;
	protected TimeField timestamp;
	protected PositionField position;

	protected ObjectField() {
	}

	/**
	 * @param msgBody byte array of on air message
	 * parse an APRS object message
	 * 
	 * builds an ObjectField instance with the parsed data
	 */
	public ObjectField(byte[] msgBody) throws Exception {
		// first, we get the object name
		this.objectName = new String(msgBody, 1, 9, StandardCharsets.ISO_8859_1).trim();
		this.live = (msgBody[10] == '*');
		// then we get the timestamp
		this.timestamp = new TimeField(msgBody, 10);
		int positionStart = 18;
		byte[] positionBody = new byte[msgBody.length - positionStart + 1];
		positionBody[0] = '!';
		System.arraycopy(msgBody, positionStart, positionBody, 1,
			msgBody.length - positionStart);
		this.position = new PositionField(positionBody, "FOO", 1);
		this.comment = position.getComment();
		this.setLastCursorPosition(positionStart + position.getLastCursorPosition() - 1);
	}

	/**
	 * 
	 * @param objectName
	 * @param live
	 * @param position
	 * @param comment
	 * 
	 * build an ObjectField with the parsed data
	 */
	public ObjectField(String objectName, boolean live, Position position, String comment) {
		this.objectName = objectName;
		this.live = live;
		this.comment = comment;
	}

	/**
	 * @return the objectName
	 */
	public String getObjectName() {
		return objectName;
	}

	/**
	 * @param objectName the objectName to set
	 */
	public void setObjectName(String objectName) {
		this.objectName = objectName;
	}

	/**
	 * @return the live
	 */
	public boolean isLive() {
		return live;
	}

	/**
	 * @param live the live to set
	 */
	public void setLive(boolean live) {
		this.live = live;
	}

	/**
	 * @return the position carried by this APRS object
	 */
	public PositionField getPosition() {
		return position == null ? null : position.copy();
	}

	
	/** 
	 * @return String
	 */
	@Override
	public String toString() {
		if (rawBytes != null)
			return new String(rawBytes, StandardCharsets.ISO_8859_1);
		return String.format(";%-9s%c%s", this.objectName, live ? '*' : '_', comment);
	}

	
	/** 
	 * @param o
	 * @return int
	 */
	@Override
	public int compareTo(APRSData o) {
		if (this.hashCode() > o.hashCode()) {
			return 1;
		}
		if (this.hashCode() == o.hashCode()) {
			return 0;
		}
		return -1;
	}

	
	/** 
	 * @return boolean
	 */
	@Override
	public boolean hasFault() {
		return this.hasFault;
	}

	
	/** 
	 * @param o
	 * @return boolean
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof ObjectField)) {
			return false;
		}
		ObjectField objectField = (ObjectField) o;
		return Objects.equals(objectName, objectField.objectName) && live == objectField.live;
	}

	
	/** 
	 * @return int
	 */
	@Override
	public int hashCode() {
		return Objects.hash(objectName, live);
	}

}
