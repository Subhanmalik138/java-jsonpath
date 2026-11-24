package com.subhanmalik.jsonpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.subhanmalik.jsonpath.Json.JsonArray;
import com.subhanmalik.jsonpath.Json.JsonBool;
import com.subhanmalik.jsonpath.Json.JsonNull;
import com.subhanmalik.jsonpath.Json.JsonNumber;
import com.subhanmalik.jsonpath.Json.JsonObject;
import com.subhanmalik.jsonpath.Json.JsonString;
import com.subhanmalik.jsonpath.Json.ParseException;
import com.subhanmalik.jsonpath.Json.Value;

class JsonTest {

    @Test
    void parsesScalars() {
        assertEquals(new JsonString("hello"), Json.parse("\"hello\""));
        assertEquals(new JsonBool(true), Json.parse("true"));
        assertEquals(new JsonBool(false), Json.parse("false"));
        assertEquals(JsonNull.INSTANCE, Json.parse("null"));
        assertEquals(42.0, ((JsonNumber) Json.parse("42")).value());
        assertEquals(-1.5, ((JsonNumber) Json.parse("-1.5")).value());
        assertEquals(1000.0, ((JsonNumber) Json.parse("1e3")).value());
    }

    @Test
    void preservesNumberLiteralsOnOutput() {
        assertEquals("1.50", Json.stringify(Json.parse("1.50")));
        assertEquals("1e3", Json.stringify(Json.parse("1e3")));
        assertEquals("42", Json.stringify(Json.parse("42")));
        assertEquals("-0.25", Json.stringify(Json.parse("-0.25")));
    }

    @Test
    void parsesNestedStructures() {
        Value value = Json.parse("{\"a\": [1, {\"b\": null}], \"c\": \"x\"}");
        assertInstanceOf(JsonObject.class, value);
        JsonObject object = (JsonObject) value;
        assertEquals(List.of("a", "c"), List.copyOf(object.members().keySet()));
        JsonArray array = (JsonArray) object.get("a");
        assertEquals(2, array.elements().size());
        assertEquals(JsonNull.INSTANCE, ((JsonObject) array.elements().get(1)).get("b"));
    }

    @Test
    void parsesEscapesAndUnicode() {
        assertEquals("Aé😀", ((JsonString) Json.parse("\"A\\u00e9\\uD83D\\uDE00\"")).value());
        assertEquals("a\"b\\c/d\ne\tf", ((JsonString) Json.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\"")).value());
    }

    @Test
    void duplicateKeysLastOneWins() {
        JsonObject object = (JsonObject) Json.parse("{\"a\": 1, \"a\": 2}");
        assertEquals(2.0, ((JsonNumber) object.get("a")).value());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ParseException.class, () -> Json.parse(""));
        assertThrows(ParseException.class, () -> Json.parse("{"));
        assertThrows(ParseException.class, () -> Json.parse("[1, 2"));
        assertThrows(ParseException.class, () -> Json.parse("\"unterminated"));
        assertThrows(ParseException.class, () -> Json.parse("01"));
        assertThrows(ParseException.class, () -> Json.parse("{\"a\": 1} extra"));
        assertThrows(ParseException.class, () -> Json.parse("\"bad \\q escape\""));
        assertThrows(ParseException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(ParseException.class, () -> Json.parse("[1,]"));
        assertThrows(ParseException.class, () -> Json.parse("\"lone \\uD83D surrogate\""));
    }

    @Test
    void prettyPrintRoundTrips() {
        String compact = "{\"store\":{\"book\":[{\"title\":\"A\"},{\"title\":\"B\"}],\"open\":true}}";
        String pretty = Json.stringifyPretty(Json.parse(compact));
        assertEquals(compact, Json.stringify(Json.parse(pretty)));
        assertTrue(pretty.contains("\n  \"store\""));
    }

    @Test
    void emptyContainersSerialize() {
        assertEquals("{}", Json.stringify(Json.parse("{}")));
        assertEquals("[]", Json.stringify(Json.parse("[]")));
        assertEquals("{}", Json.stringifyPretty(Json.parse("{}")));
        assertEquals("[]", Json.stringifyPretty(Json.parse("[]")));
    }

    @Test
    void deeplyNestedInputIsRejected() {
        String input = "[".repeat(600) + "]".repeat(600);
        assertThrows(ParseException.class, () -> Json.parse(input));
    }

    @Test
    void stringifyEscapesControlCharacters() {
        Map<String, Value> members = Map.of("s", new JsonString("line\nbreak\t\"q\""));
        assertEquals("{\"s\":\"line\\nbreak\\t\\\"q\\\"\"}", Json.stringify(new JsonObject(members)));
    }
}
