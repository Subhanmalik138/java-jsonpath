package com.subhanmalik.jsonpath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, immutable JSON value model with a strict hand-rolled parser
 * and serializer. Zero dependencies.
 *
 * <p>Numbers keep their original literal text so serialization is faithful
 * (e.g. {@code 1.50} stays {@code 1.50}, {@code 1e3} stays {@code 1e3});
 * comparisons use the parsed {@code double} value.
 */
public final class Json {

    private Json() {
    }

    /** Base type for every JSON value. */
    public sealed interface Value permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBool, JsonNull {
    }

    public record JsonObject(Map<String, Value> members) implements Value {
        public JsonObject {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        public Value get(String key) {
            return members.get(key);
        }

        public boolean contains(String key) {
            return members.containsKey(key);
        }
    }

    public record JsonArray(List<Value> elements) implements Value {
        public JsonArray {
            elements = List.copyOf(elements);
        }
    }

    public record JsonString(String value) implements Value {
    }

    public record JsonNumber(double value, String raw) implements Value {
        public JsonNumber(double value) {
            this(value, format(value));
        }

        private static String format(double value) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
    }

    public record JsonBool(boolean value) implements Value {
    }

    public record JsonNull() implements Value {
        public static final JsonNull INSTANCE = new JsonNull();
    }

    /** Thrown when input is not well-formed JSON; carries the failing offset. */
    public static final class ParseException extends RuntimeException {
        private final int position;

        public ParseException(String message, int position) {
            super(message + " (at offset " + position + ")");
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    /** Maximum nesting depth, guards against stack exhaustion on hostile input. */
    public static final int MAX_DEPTH = 512;

    /** Parse a complete JSON document. Trailing non-whitespace is an error. */
    public static Value parse(String input) {
        Parser parser = new Parser(input);
        parser.skipWhitespace();
        Value value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new ParseException("Unexpected trailing content", parser.pos);
        }
        return value;
    }

    /** Serialize a value as compact JSON. */
    public static String stringify(Value value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, -1);
        return out.toString();
    }

    /** Serialize a value as indented JSON (two spaces per level). */
    public static String stringifyPretty(Value value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, 2);
        return out.toString();
    }

    private static void write(Value value, StringBuilder out, int depth, int indent) {
        if (value instanceof JsonNull) {
            out.append("null");
        } else if (value instanceof JsonBool b) {
            out.append(b.value());
        } else if (value instanceof JsonNumber n) {
            out.append(n.raw());
        } else if (value instanceof JsonString s) {
            writeString(s.value(), out);
        } else if (value instanceof JsonArray a) {
            writeArray(a, out, depth, indent);
        } else if (value instanceof JsonObject o) {
            writeObject(o, out, depth, indent);
        } else {
            throw new IllegalStateException("Unknown JSON value: " + value);
        }
    }

    private static void writeArray(JsonArray array, StringBuilder out, int depth, int indent) {
        out.append('[');
        boolean first = true;
        for (Value element : array.elements()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            if (indent >= 0) {
                out.append('\n').append(" ".repeat(indent * (depth + 1)));
            }
            write(element, out, depth + 1, indent);
        }
        if (indent >= 0 && !array.elements().isEmpty()) {
            out.append('\n').append(" ".repeat(indent * depth));
        }
        out.append(']');
    }

    private static void writeObject(JsonObject object, StringBuilder out, int depth, int indent) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Value> entry : object.members().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            if (indent >= 0) {
                out.append('\n').append(" ".repeat(indent * (depth + 1)));
            }
            writeString(entry.getKey(), out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            write(entry.getValue(), out, depth + 1, indent);
        }
        if (indent >= 0 && !object.members().isEmpty()) {
            out.append('\n').append(" ".repeat(indent * depth));
        }
        out.append('}');
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Recursive-descent JSON parser. */
    private static final class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
        }

        boolean atEnd() {
            return pos >= input.length();
        }

        void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Value parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new ParseException("JSON is nested too deeply", pos);
            }
            if (atEnd()) {
                throw new ParseException("Unexpected end of input", pos);
            }
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> new JsonString(parseString());
                case 't' -> parseLiteral("true", new JsonBool(true));
                case 'f' -> parseLiteral("false", new JsonBool(false));
                case 'n' -> parseLiteral("null", JsonNull.INSTANCE);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new ParseException("Unexpected character '" + c + "'", pos);
                }
            };
        }

        JsonObject parseObject(int depth) {
            pos++; // consume '{'
            Map<String, Value> members = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && input.charAt(pos) == '}') {
                pos++;
                return new JsonObject(members);
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(pos) != '"') {
                    throw new ParseException("Expected string key in object", pos);
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || input.charAt(pos) != ':') {
                    throw new ParseException("Expected ':' after object key", pos);
                }
                pos++;
                skipWhitespace();
                // Last duplicate key wins, matching common JSON behaviour.
                members.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new ParseException("Unterminated object", pos);
                }
                char c = input.charAt(pos);
                if (c == '}') {
                    pos++;
                    return new JsonObject(members);
                }
                if (c != ',') {
                    throw new ParseException("Expected ',' or '}' in object", pos);
                }
                pos++;
            }
        }

        JsonArray parseArray(int depth) {
            pos++; // consume '['
            List<Value> elements = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && input.charAt(pos) == ']') {
                pos++;
                return new JsonArray(elements);
            }
            while (true) {
                skipWhitespace();
                elements.add(parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new ParseException("Unterminated array", pos);
                }
                char c = input.charAt(pos);
                if (c == ']') {
                    pos++;
                    return new JsonArray(elements);
                }
                if (c != ',') {
                    throw new ParseException("Expected ',' or ']' in array", pos);
                }
                pos++;
            }
        }

        String parseString() {
            pos++; // consume opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new ParseException("Unterminated string", pos);
                }
                char c = input.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new ParseException("Unterminated escape in string", pos);
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> out.append(parseUnicodeEscape());
                        default -> throw new ParseException("Invalid escape '\\" + esc + "'", pos - 1);
                    }
                } else if (c < 0x20) {
                    throw new ParseException("Unescaped control character in string", pos - 1);
                } else {
                    out.append(c);
                }
            }
        }

        private String parseUnicodeEscape() {
            int code = parseHex4();
            if (Character.isHighSurrogate((char) code)) {
                // Expect a low surrogate right after: \uD83D\uDE00
                if (pos + 1 < input.length() && input.charAt(pos) == '\\' && input.charAt(pos + 1) == 'u') {
                    pos += 2;
                    int low = parseHex4();
                    if (Character.isLowSurrogate((char) low)) {
                        return new String(new char[]{(char) code, (char) low});
                    }
                }
                throw new ParseException("Lone high surrogate in \\u escape", pos);
            }
            if (Character.isLowSurrogate((char) code)) {
                throw new ParseException("Lone low surrogate in \\u escape", pos);
            }
            return String.valueOf((char) code);
        }

        private int parseHex4() {
            if (pos + 4 > input.length()) {
                throw new ParseException("Truncated \\u escape", pos);
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = input.charAt(pos++);
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw new ParseException("Invalid hex digit '" + c + "' in \\u escape", pos - 1);
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private Value parseLiteral(String literal, Value value) {
            if (!input.startsWith(literal, pos)) {
                throw new ParseException("Invalid literal, expected '" + literal + "'", pos);
            }
            pos += literal.length();
            return value;
        }

        private JsonNumber parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= input.length()) {
                throw new ParseException("Truncated number", pos);
            }
            if (input.charAt(pos) == '0') {
                pos++;
            } else if (input.charAt(pos) >= '1' && input.charAt(pos) <= '9') {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            } else {
                throw new ParseException("Invalid number", pos);
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new ParseException("Expected digit after decimal point", pos);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new ParseException("Expected digit in exponent", pos);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String raw = input.substring(start, pos);
            double value;
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new ParseException("Number out of range: " + raw, start);
            }
            return new JsonNumber(value, raw);
        }
    }
}
