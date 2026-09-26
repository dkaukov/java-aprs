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

package io.github.dkaukov.aprs.parser;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

public final class ItemField extends APRSData {

    private ItemField(ItemField source) {
        super(source);
        this.live = source.live;
        this.objectName = source.objectName;
    }

    @Override public ItemField copy() {
        return new ItemField(this);
    }

    private static final long serialVersionUID = 1L;
    private boolean live = true;
    private String objectName;

    /**
     * @param msgBody byte array of the on-air message
     * @throws Exception if it is unable to parse the item field from the msg
     *
     * parse an APRS item message
     */
    public ItemField(byte[] msgBody) throws Exception {
        super(msgBody);
        String body = new String(msgBody, StandardCharsets.ISO_8859_1);
        int name_length = body.indexOf("!") - 1;
        if (name_length < 1 || name_length > 9) {
            name_length = body.indexOf("_");
            if (name_length < 1 || name_length > 9) {
                throw new Exception("Invalid ITEM packet, missing '!' or '_'.");
            }
            this.live = false;
        } else {
            this.live = true;
        }
        this.objectName = new String(msgBody, 1, name_length, StandardCharsets.ISO_8859_1).trim();
        int cursor = name_length + 2;
        comment = new String(msgBody, cursor, msgBody.length - cursor, "UTF-8").trim();
    }


    /**
     * @return String
     */
    @Override
    public String toString() {
        if (rawBytes != null) {
            return new String(rawBytes, StandardCharsets.ISO_8859_1);
        }
        return ")" + this.objectName + (live ? "!" : "_") + comment;
    }


    @Override
    public boolean hasFault() {
        return this.hasFault;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ItemField)) {
            return false;
        }
        ItemField itemField = (ItemField) o;
        return live == itemField.live && Objects.equals(objectName, itemField.objectName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(live, objectName);
    }

}
