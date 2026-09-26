# Android integration changes before 0.1.0

The SpotBugs exclusions have been removed. These changes require recompiling
and updating consumers of the snapshot library.

## Event and packet models

`AprsEvent` and `AprsPacket` are final. Their instance fields are package-private;
use public JavaBean getters and setters from the Android app:

```java
event.setDeliveryState(AprsEvent.DELIVERY_DELIVERED);
long id = event.getId();
boolean internetOnly = event.isInternetOnly();
packet.setRawAx25(frame);
```

All former public instance fields have accessors. Boolean getters use `is`,
including `isDigipeated()`. Update persistence mappers accordingly. Both models
provide a copy constructor and `copy()`. `AprsPacket` copies `rawAx25` on input,
output, and copying; modifying an array returned by `getRawAx25()` has no effect
until you call `setRawAx25()`.

## Parser ownership

`APRSPacket.getPayload()`, `ThirdPartyField.getInnerPacket()`, position getters,
and extension getters return independent copies. Digipeater lists, field maps,
and type sets are unmodifiable snapshots; their mutable elements are also copied.
Constructors, setters, and `addAprsData()` copy mutable inputs.

Replace `packet.getDigipeaters().add(digi)` with `packet.addDigipeater(digi)`.
To replace or reorder a complete path, construct a new packet with that path.
To edit parsed position metadata, explicitly commit each updated snapshot:

```java
InformationField payload = packet.getPayload();
PositionField field = (PositionField) payload.getAprsData(APRSTypes.T_POSITION);
Position position = field.getPosition();
position.setAltitude(100);
field.setPosition(position);
payload.addAprsData(APRSTypes.T_POSITION, field);
packet.setPayload(payload);
```

`setPayload()` requires the original wire bytes to match; it updates parsed
metadata, not encoded AX.25 bytes. Build a new packet when changing on-air content.
`copy()` preserves parsed state, packet timestamps, and original input text.

Concrete APRS data fields, data extensions, message/unsupported information
fields, and `APRSPacket` are now final. Use composition instead of subclassing
those classes. Custom `APRSData` and `DataExtension` implementations must implement
`copy()`; custom `InformationField` subclasses must override it to preserve their
state and independently copy mutable children.

## Repository contract

`AprsController` constructor and repository interfaces are unchanged. The
controller now passes snapshots to `insert()`/`update()` and copies records
returned by queries. Implement `update()` to persist the supplied record by ID;
do not rely on edits to previously returned objects becoming visible by identity.
`insert()` must return the assigned ID. The backing store remains shared, so
later queries still observe committed app-side changes.

## Verification

Run `mvn clean verify -Dbasepom.javadoc.skip=false`, then `mvn install` to update
Maven Local before rebuilding Android. The build disables Basepom's inherited
SpotBugs exclusion list as well as removing the project-specific filter.
