package io.github.dkaukov.aprs.parser;

/**
 * Represents a third-party relayed APRS packet (Data Type Identifier '}').
 * The inner packet is parsed recursively, and the carrying station is appended
 * to the inner packet's digipeater path per the APRS specification.
 */
public final class ThirdPartyField extends APRSData {

    private ThirdPartyField(ThirdPartyField source) {
        super(source);
        this.innerPacket = source.innerPacket == null ? null : source.innerPacket.copy();
    }

    @Override public ThirdPartyField copy() {
        return new ThirdPartyField(this);
    }

    private static final long serialVersionUID = 1L;
    private final APRSPacket innerPacket;

    public ThirdPartyField(byte[] rawBytes, APRSPacket innerPacket) {
        super(rawBytes);
        this.innerPacket = innerPacket == null ? null : innerPacket.copy();
        this.type = APRSTypes.T_THIRDPARTY;
        setLastCursorPosition(rawBytes.length);
    }

    /**
     * @return the recursively parsed inner APRSPacket
     */
    public APRSPacket getInnerPacket() {
        return innerPacket == null ? null : innerPacket.copy();
    }

    @Override
    public String toString() {
        return "ThirdPartyField{" + innerPacket + '}';
    }

    @Override
    public boolean hasFault() {
        return innerPacket != null && innerPacket.hasFault();
    }
}
