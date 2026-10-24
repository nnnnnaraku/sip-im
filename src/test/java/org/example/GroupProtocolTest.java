package org.example;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.Assert.*;

public class GroupProtocolTest {
    private static final String GROUP = "11111111-1111-1111-1111-111111111111";
    private static final String MESSAGE = "22222222-2222-2222-2222-222222222222";
    private static final String TOKEN = "33333333-3333-3333-3333-333333333333";

    private GroupMessage base(String type) {
        GroupMessage value = new GroupMessage();
        value.type = type;
        value.groupId = GROUP;
        value.messageId = MESSAGE;
        value.token = TOKEN;
        value.senderId = "100";
        value.ownerId = "100";
        value.revision = 1;
        value.timestamp = 1;
        return value;
    }

    private GroupMessage invite() {
        GroupMessage value = base("INVITE");
        value.name = "项目讨论";
        value.members = new LinkedHashMap<String, String>();
        value.members.put("100", "ACTIVE");
        value.members.put("101", "INVITED");
        value.members.put("102", "INVITED");
        value.closed = false;
        return value;
    }

    private String textWire() {
        GroupMessage value = base("TEXT");
        value.text = "hello";
        return GroupProtocol.encode(value);
    }

    private void invalidWire(String wire) {
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.decode(wire));
    }

    private void invalidMessage(GroupMessage value) {
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.encode(value));
    }

    @Test public void threeUserInvitationRoundTrips() {
        GroupMessage parsed = GroupProtocol.decode(GroupProtocol.encode(invite()));
        assertEquals(3, parsed.members.size());
        assertEquals("ACTIVE", parsed.members.get("100"));
        assertEquals("INVITED", parsed.members.get("102"));
        assertEquals("项目讨论", parsed.name);
        assertFalse(parsed.closed);
    }

    @Test public void textPreservesChineseEnglishSpecialCharactersAndWhitespace() {
        GroupMessage value = base("TEXT");
        value.text = "  中文 English <script> & \"quoted\" \\ \n\t😀  ";
        String wire = GroupProtocol.encode(value);
        assertTrue(wire.contains("<script>"));
        assertEquals(value.text, GroupProtocol.decode(wire).text);
    }

    @Test public void textUtf8BoundaryIsIndependentOfJavaCharCount() {
        assertEquals(repeat("中", 133), GroupProtocol.text(repeat("中", 133)));
        assertEquals(repeat("a", 400), GroupProtocol.text(repeat("a", 400)));
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.text(repeat("中", 134)));
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.text(repeat("a", 401)));
    }

    @Test public void supplementaryUnicodeAtByteBoundaryRoundTrips() {
        GroupMessage value = base("TEXT");
        value.text = repeat("😀", 100);
        assertEquals(400, value.text.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(value.text, GroupProtocol.decode(GroupProtocol.encode(value)).text);
    }

    @Test public void namesTrimButDoNotSilentlyRemoveUnsafeControls() {
        assertEquals("研发组", GroupProtocol.name(" 研发组 \t"));
        assertEquals(repeat("中", 16), GroupProtocol.name(repeat("中", 16)));
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.name(repeat("中", 17)));
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.name("\rname"));
        GroupMessage value = invite();
        value.name = " group ";
        invalidMessage(value);
    }

    @Test public void emptyAndWhitespaceOnlyTextAndNameAreRejected() {
        for (String value : new String[]{null, "", " \n\t "}) {
            assertThrows(IllegalArgumentException.class, () -> GroupProtocol.text(value));
            assertThrows(IllegalArgumentException.class, () -> GroupProtocol.name(value));
        }
    }

    @Test public void sipUsersCannotInjectHeadersOrUris() {
        assertEquals("u-100.x_1", GroupProtocol.user("u-100.x_1"));
        for (String value : new String[]{null, "", "100@host", "sip:100", "100\r\nX:1",
                "a/b", "a b", repeat("a", 17), "用户"}) {
            assertThrows(IllegalArgumentException.class, () -> GroupProtocol.user(value));
        }
    }

    @Test public void idsMustBeCanonicalFullLowercaseUuid() {
        for (String value : new String[]{null, "1-1-1-1-1", "abcdefab-abcd-abcd-abcd-abcdefabcdef".toUpperCase(),
                "" + GROUP + " ", "not-a-uuid"}) {
            GroupMessage message = base("SYNC");
            message.groupId = value;
            invalidMessage(message);
            message = base("SYNC");
            message.messageId = value;
            invalidMessage(message);
            message = base("SYNC");
            message.token = value;
            invalidMessage(message);
        }
    }

    @Test public void everySupportedTypeRoundTripsWithOnlyApplicableFields() {
        for (String type : new String[]{"ACCEPT", "LEAVE", "SYNC"}) {
            assertEquals(type, GroupProtocol.decode(GroupProtocol.encode(base(type))).type);
        }
        GroupMessage state = invite();
        state.type = "STATE";
        state.members.put("101", "LEFT");
        assertEquals("LEFT", GroupProtocol.decode(GroupProtocol.encode(state)).members.get("101"));
        state.closed = true;
        state.members.put("100", "LEFT");
        assertTrue(GroupProtocol.decode(GroupProtocol.encode(state)).closed);
    }

    @Test public void acknowledgementHasValidReferenceAndSafeStatusCode() {
        GroupMessage ack = base("ACK");
        ack.ackForId = UUID.randomUUID().toString();
        ack.ackStatus = "DELIVERED";
        assertEquals(ack.ackForId, GroupProtocol.decode(GroupProtocol.encode(ack)).ackForId);
        ack.ackStatus = "REJECTED";
        ack.ackDetail = "NOT_MEMBER";
        assertEquals("NOT_MEMBER", GroupProtocol.decode(GroupProtocol.encode(ack)).ackDetail);
        ack.ackDetail = "password=secret";
        invalidMessage(ack);
        ack.ackDetail = null;
        ack.ackStatus = "SUCCESS";
        invalidMessage(ack);
        ack.ackStatus = "DELIVERED";
        ack.ackForId = "short";
        invalidMessage(ack);
    }

    @Test public void fieldsForOtherTypesAreRejected() {
        GroupMessage value = base("SYNC");
        value.text = "invalid";
        invalidMessage(value);
        value = base("SYNC"); value.closed = false; invalidMessage(value);
        value = base("SYNC"); value.name = "invalid"; invalidMessage(value);
        value = base("SYNC"); value.members = invite().members; invalidMessage(value);
        value = base("SYNC"); value.ackDetail = "INVALID"; invalidMessage(value);
        value = base("SYNC"); value.ackStatus = "DELIVERED"; invalidMessage(value);
        value = base("SYNC"); value.ackForId = MESSAGE; invalidMessage(value);
        value = invite(); value.text = "invalid"; invalidMessage(value);
    }

    @Test public void minimumMaximumMembersAndOwnerAreChecked() {
        GroupMessage value = invite();
        value.members.remove("102"); invalidMessage(value);
        value = invite(); value.members.remove("100"); value.members.put("103", "ACTIVE"); invalidMessage(value);
        value = invite(); value.members.put("100", "INVITED"); invalidMessage(value);
        value = invite(); value.closed = true; invalidMessage(value);
        value = invite(); value.members.put("102", "ONLINE"); invalidMessage(value);
        value = invite(); value.members.put("102", null); invalidMessage(value);
        value = invite(); value.members.put("bad@user", "ACTIVE"); invalidMessage(value);
        value = invite();
        for (int i = 103; i <= 107; i++) value.members.put(String.valueOf(i), "INVITED");
        assertEquals(8, GroupProtocol.decode(GroupProtocol.encode(value)).members.size());
        value.members.put("108", "INVITED"); invalidMessage(value);
    }

    @Test public void missingVersionAndBaseFieldsAreRejected() {
        String wire = textWire();
        invalidWire(wire.replace("\"version\":1,", ""));
        invalidWire(wire.replace("\"revision\":1,", ""));
        invalidWire(wire.replace("\"timestamp\":1,", ""));
        invalidWire(wire.replace("\"senderId\":\"100\",", ""));
        invalidWire(wire.replace("\"ownerId\":\"100\",", ""));
    }

    @Test public void invalidVersionsRevisionsAndTimestampsAreRejected() {
        invalidWire(textWire().replace("\"version\":1", "\"version\":2"));
        invalidWire(textWire().replace("\"revision\":1", "\"revision\":0"));
        invalidWire(textWire().replace("\"revision\":1", "\"revision\":1000000001"));
        invalidWire(textWire().replace("\"timestamp\":1", "\"timestamp\":0"));
        invalidWire(textWire().replace("\"timestamp\":1", "\"timestamp\":9223372036854775808"));
        GroupMessage value = base("SYNC");
        value.revision = 1000000000;
        value.timestamp = Long.MAX_VALUE;
        assertEquals(Long.MAX_VALUE, GroupProtocol.decode(GroupProtocol.encode(value)).timestamp);
    }

    @Test public void allJsonNumberCoercionsAreRejected() {
        for (String number : new String[]{"\"1\"", "1.0", "1e0", "true", "null", "[]", "{}", "-1", "01"}) {
            invalidWire(textWire().replace("\"revision\":1", "\"revision\":" + number));
        }
        invalidWire(textWire().replace("\"text\":\"hello\"", "\"text\":101"));
        invalidWire(GroupProtocol.encode(invite()).replace("\"closed\":false", "\"closed\":\"false\""));
    }

    @Test public void nullAndWrongTypesAreRejected() {
        invalidWire("null"); invalidWire("[]"); invalidWire("true"); invalidWire("\"hello\"");
        invalidWire(textWire().replace("\"text\":\"hello\"", "\"text\":null"));
        invalidWire(textWire().replace("\"text\":\"hello\"", "\"text\":{}"));
        invalidWire(GroupProtocol.encode(invite()).replace("\"INVITED\"", "null"));
    }

    @Test public void duplicateTopLevelAndEscapedEquivalentKeysAreRejected() {
        invalidWire(textWire().replace("\"version\":1", "\"version\":1,\"version\":1"));
        invalidWire(textWire().replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"));
    }

    @Test public void duplicateMemberKeysAreRejected() {
        invalidWire(GroupProtocol.encode(invite()).replace("\"101\":\"INVITED\"",
                "\"101\":\"INVITED\",\"101\":\"ACTIVE\""));
    }

    @Test public void unknownAndUnknownTypeFieldsAreRejected() {
        invalidWire(textWire().replace("\"version\":1", "\"version\":1,\"extra\":0"));
        invalidWire(textWire().replace("\"TEXT\"", "\"FILE\""));
    }

    @Test public void relaxedJsonSyntaxAndTrailingDataAreRejected() {
        invalidWire(textWire().replace("\"version\"", "version"));
        invalidWire(textWire().replace("\"type\":\"TEXT\"", "\"type\":'TEXT'"));
        invalidWire("/*comment*/" + textWire());
        invalidWire(textWire() + "{}");
        invalidWire(textWire().substring(0, textWire().length() - 1) + ",}");
        invalidWire(textWire().replace("hello", "hello\nworld"));
        invalidWire(textWire().replace("hello", "hello\tworld"));
    }

    @Test public void wireSizeIsCheckedUsingUtf8Bytes() {
        String exact = textWire();
        exact += repeat(" ", GroupProtocol.MAX_WIRE_BYTES - exact.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(GroupProtocol.MAX_WIRE_BYTES, exact.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("hello", GroupProtocol.decode(exact).text);
        invalidWire(exact + " ");
        invalidWire(repeat(" ", GroupProtocol.MAX_WIRE_BYTES + 1));
        String unicodePadding = repeat("中", 300);
        assertTrue(unicodePadding.length() < GroupProtocol.MAX_WIRE_BYTES);
        invalidWire(unicodePadding);
        GroupMessage value = base("TEXT"); value.text = repeat("\n", 399) + "a";
        invalidMessage(value); // JSON escaping can exceed the envelope cap despite valid text bytes.
    }

    @Test public void loneSurrogatesRawOrEscapedAndUnsafeControlsAreRejected() {
        for (String bad : new String[]{"\ud800", "\udc00", "x\u0000", "x\r", "x\u007f", "x\u0085", "x\u202e"}) {
            assertThrows(IllegalArgumentException.class, () -> GroupProtocol.text(bad));
        }
        invalidWire(textWire().replace("hello", "\\ud800"));
        invalidWire(textWire().replace("hello", "\\udc00"));
        invalidWire(textWire().replace("hello", "\\u0000"));
        invalidWire(textWire().replace("hello", "\ud800"));
    }

    @Test public void errorMessagesDoNotEchoTokenOrRawPayload() {
        try {
            GroupProtocol.decode(textWire().replace("\"text\":\"hello\"", "\"secret\":\"private\""));
            fail("Expected rejection");
        } catch (IllegalArgumentException exception) {
            assertFalse(exception.getMessage().contains(TOKEN));
            assertFalse(exception.getMessage().contains("private"));
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
