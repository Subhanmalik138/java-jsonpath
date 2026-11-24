package com.subhanmalik.jsonpath;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.subhanmalik.jsonpath.Json.JsonArray;
import com.subhanmalik.jsonpath.Json.JsonBool;
import com.subhanmalik.jsonpath.Json.JsonNull;
import com.subhanmalik.jsonpath.Json.JsonNumber;
import com.subhanmalik.jsonpath.Json.JsonObject;
import com.subhanmalik.jsonpath.Json.JsonString;
import com.subhanmalik.jsonpath.Json.Value;

/**
 * A zero-dependency JSONPath query engine implementing the RFC 9535
 * expression language: child segments, wildcards, slices, unions,
 * recursive descent, and filter expressions with functions.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Query and filter parsing are two small recursive-descent parsers;
 *       no grammar generator, no runtime dependencies.</li>
 *   <li>Every match carries its normalized path ({@code $['store']['book'][0]}),
 *       so callers can locate results without re-walking the document.</li>
 *   <li>Filter comparisons follow RFC 9535: a comparison is true when any
 *       pair from the two operand nodelists satisfies it, and any comparison
 *       involving a missing node (Nothing) is false — including {@code !=}.</li>
 * </ul>
 */
public final class JsonPath {

    private JsonPath() {
    }

    /** One query hit: the matched node and its normalized path. */
    public record Match(Value node, String normalizedPath) {
    }

    /** Thrown when a query string is not well-formed JSONPath. */
    public static final class QueryException extends RuntimeException {
        public QueryException(String message) {
            super(message);
        }
    }

    /** Evaluate a JSONPath query against a document root. */
    public static List<Match> query(Value root, String queryText) {
        List<Segment> segments = new QueryParser(queryText).parseQuery();
        List<Match> current = List.of(new Match(root, "$"));
        for (Segment segment : segments) {
            List<Match> next = new ArrayList<>();
            for (Match match : current) {
                next.addAll(apply(segment, match, root));
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    /** Evaluate a query and return just the matched values. */
    public static List<Value> queryValues(Value root, String queryText) {
        return query(root, queryText).stream().map(Match::node).toList();
    }

    /** Evaluate a query and return just the normalized paths of the matches. */
    public static List<String> queryPaths(Value root, String queryText) {
        return query(root, queryText).stream().map(Match::normalizedPath).toList();
    }

    // ------------------------------------------------------------------
    // Segments
    // ------------------------------------------------------------------

    private sealed interface Segment permits Name, Index, Wildcard, Slice, Union, Filter, Descend {
    }

    private record Name(String name) implements Segment {
    }

    private record Index(int index) implements Segment {
    }

    private record Wildcard() implements Segment {
    }

    private record Slice(Integer start, Integer end, Integer step) implements Segment {
    }

    private record Union(List<Segment> members) implements Segment {
    }

    private record Filter(FilterExpr expr) implements Segment {
    }

    private record Descend(Segment inner) implements Segment {
    }

    private static List<Match> apply(Segment segment, Match match, Value root) {
        Value node = match.node();
        String path = match.normalizedPath();
        if (segment instanceof Name name) {
            if (node instanceof JsonObject obj && obj.contains(name.name())) {
                return List.of(new Match(obj.get(name.name()), path + bracket(name.name())));
            }
            return List.of();
        }
        if (segment instanceof Index index) {
            if (node instanceof JsonArray arr) {
                int i = index.index() < 0 ? arr.elements().size() + index.index() : index.index();
                if (i >= 0 && i < arr.elements().size()) {
                    return List.of(new Match(arr.elements().get(i), path + "[" + i + "]"));
                }
            }
            return List.of();
        }
        if (segment instanceof Wildcard) {
            return childrenOf(node, path);
        }
        if (segment instanceof Slice slice) {
            if (node instanceof JsonArray arr) {
                List<Match> out = new ArrayList<>();
                for (int i : sliceIndices(slice, arr.elements().size())) {
                    out.add(new Match(arr.elements().get(i), path + "[" + i + "]"));
                }
                return out;
            }
            return List.of();
        }
        if (segment instanceof Union union) {
            List<Match> out = new ArrayList<>();
            for (Segment member : union.members()) {
                out.addAll(apply(member, match, root));
            }
            return out;
        }
        if (segment instanceof Descend descend) {
            List<Match> out = new ArrayList<>();
            for (Match descendant : selfAndDescendants(match)) {
                out.addAll(apply(descend.inner(), descendant, root));
            }
            return out;
        }
        if (segment instanceof Filter filter) {
            List<Match> out = new ArrayList<>();
            for (Match child : childrenOf(node, path)) {
                if (filter.expr().evaluate(child.node(), root)) {
                    out.add(child);
                }
            }
            return out;
        }
        throw new IllegalStateException("Unknown segment: " + segment);
    }

    /** Member values of an object (in insertion order) or elements of an array. */
    private static List<Match> childrenOf(Value node, String path) {
        List<Match> out = new ArrayList<>();
        if (node instanceof JsonObject obj) {
            for (var entry : obj.members().entrySet()) {
                out.add(new Match(entry.getValue(), path + bracket(entry.getKey())));
            }
        } else if (node instanceof JsonArray arr) {
            for (int i = 0; i < arr.elements().size(); i++) {
                out.add(new Match(arr.elements().get(i), path + "[" + i + "]"));
            }
        }
        return out;
    }

    /** The node itself followed by every descendant, depth-first, in document order. */
    private static List<Match> selfAndDescendants(Match match) {
        List<Match> out = new ArrayList<>();
        out.add(match);
        for (Match child : childrenOf(match.node(), match.normalizedPath())) {
            out.addAll(selfAndDescendants(child));
        }
        return out;
    }

    /** RFC 9535 slice normalization and iteration. */
    private static List<Integer> sliceIndices(Slice slice, int length) {
        int step = slice.step() == null ? 1 : slice.step();
        if (step == 0) {
            return List.of();
        }
        int start;
        int end;
        if (step > 0) {
            start = slice.start() == null ? 0 : normalizeBound(slice.start(), length, 0, length);
            end = slice.end() == null ? length : normalizeBound(slice.end(), length, 0, length);
        } else {
            start = slice.start() == null ? length - 1 : normalizeBound(slice.start(), length, -1, length - 1);
            end = slice.end() == null ? -1 : normalizeBound(slice.end(), length, -1, length - 1);
        }
        List<Integer> out = new ArrayList<>();
        if (step > 0) {
            for (int i = start; i < end; i += step) {
                out.add(i);
            }
        } else {
            for (int i = start; i > end; i += step) {
                out.add(i);
            }
        }
        return out;
    }

    private static int normalizeBound(int value, int length, int low, int high) {
        if (value < 0) {
            return Math.max(length + value, low);
        }
        return Math.min(value, high);
    }

    /** Normalized-path form of a member name: {@code ['store']}. */
    private static String bracket(String name) {
        return "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    // ------------------------------------------------------------------
    // Query parser
    // ------------------------------------------------------------------

    private static final class QueryParser {
        private final String text;
        private int pos;

        QueryParser(String text) {
            this.text = text;
        }

        List<Segment> parseQuery() {
            if (text.isEmpty() || text.charAt(0) != '$') {
                throw new QueryException("Query must start with '$'");
            }
            pos = 1;
            List<Segment> segments = parseSegments();
            if (pos != text.length()) {
                throw new QueryException("Unexpected trailing characters at offset " + pos);
            }
            return segments;
        }

        /** Parse segments until the end of the query, ']' (in filters) or an error. */
        List<Segment> parseSegments() {
            List<Segment> segments = new ArrayList<>();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '.') {
                    if (peek(1) == '.') {
                        pos += 2;
                        segments.add(new Descend(parseDescendTarget()));
                    } else {
                        pos++;
                        segments.add(parseShorthand());
                    }
                } else if (c == '[') {
                    segments.addAll(parseBracket());
                } else {
                    break;
                }
            }
            return segments;
        }

        private Segment parseShorthand() {
            if (pos >= text.length()) {
                throw new QueryException("Query ends after '.'");
            }
            if (text.charAt(pos) == '*') {
                pos++;
                return new Wildcard();
            }
            return new Name(parseBareName());
        }

        private Segment parseDescendTarget() {
            if (pos >= text.length()) {
                throw new QueryException("Query ends after '..'");
            }
            char c = text.charAt(pos);
            if (c == '*') {
                pos++;
                return new Wildcard();
            }
            if (c == '[') {
                List<Segment> inner = parseBracket();
                if (inner.size() != 1) {
                    throw new QueryException("Union is not allowed after '..'");
                }
                return inner.get(0);
            }
            return new Name(parseBareName());
        }

        private String parseBareName() {
            int start = pos;
            while (pos < text.length() && isNameChar(text.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw new QueryException("Expected a member name at offset " + pos);
            }
            return text.substring(start, pos);
        }

        private static boolean isNameChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c >= 0x80;
        }

        private List<Segment> parseBracket() {
            pos++; // consume '['
            skipWhitespace();
            if (pos >= text.length()) {
                throw new QueryException("Unterminated '['");
            }
            if (text.charAt(pos) == '?') {
                pos++;
                FilterExpr expr = new FilterParser(text, pos).parseExpression();
                pos = exprEndPos(expr);
                skipWhitespace();
                expect(']');
                return List.of(new Filter(expr));
            }
            List<Segment> members = new ArrayList<>();
            while (true) {
                skipWhitespace();
                members.add(parseBracketSelector());
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new QueryException("Unterminated '['");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return members.size() == 1 ? members : List.of(new Union(members));
                }
                throw new QueryException("Expected ',' or ']' at offset " + pos);
            }
        }

        // FilterParser shares the cursor; this records where it stopped.
        private int filterEndPos = -1;

        private int exprEndPos(FilterExpr expr) {
            return filterEndPos;
        }

        private Segment parseBracketSelector() {
            if (pos >= text.length()) {
                throw new QueryException("Unterminated '['");
            }
            char c = text.charAt(pos);
            if (c == '*' ) {
                pos++;
                return new Wildcard();
            }
            if (c == '\'' || c == '"') {
                return new Name(parseQuotedString());
            }
            if (c == ':') {
                return parseSlice(null);
            }
            if (c == '-' || Character.isDigit(c)) {
                int start = pos;
                if (c == '-') {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == start || (pos == start + 1 && text.charAt(start) == '-')) {
                    throw new QueryException("Expected a number at offset " + start);
                }
                int first = Integer.parseInt(text.substring(start, pos));
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ':') {
                    return parseSlice(first);
                }
                return new Index(first);
            }
            throw new QueryException("Unexpected character '" + c + "' in brackets at offset " + pos);
        }

        private Slice parseSlice(Integer first) {
            expect(':');
            Integer second = parseOptionalInt();
            Integer third = null;
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ':') {
                pos++;
                third = parseOptionalInt();
            }
            return new Slice(first, second, third);
        }

        private Integer parseOptionalInt() {
            skipWhitespace();
            if (pos >= text.length()) {
                return null;
            }
            char c = text.charAt(pos);
            if (c != '-' && !Character.isDigit(c)) {
                return null;
            }
            int start = pos;
            if (c == '-') {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == start || (pos == start + 1 && text.charAt(start) == '-')) {
                throw new QueryException("Expected a number at offset " + start);
            }
            return Integer.parseInt(text.substring(start, pos));
        }

        private String parseQuotedString() {
            char quote = text.charAt(pos);
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new QueryException("Unterminated string literal");
                }
                char c = text.charAt(pos++);
                if (c == quote) {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new QueryException("Unterminated escape in string literal");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case '/' -> out.append('/');
                        case '\\' -> out.append('\\');
                        case '\'' -> out.append('\'');
                        case '"' -> out.append('"');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new QueryException("Truncated \\u escape");
                            }
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(text.charAt(pos++), 16);
                                if (digit < 0) {
                                    throw new QueryException("Invalid hex digit in \\u escape");
                                }
                                code = (code << 4) | digit;
                            }
                            out.append((char) code);
                        }
                        default -> throw new QueryException("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void expect(char expected) {
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new QueryException("Expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }

        private char peek(int ahead) {
            return pos + ahead < text.length() ? text.charAt(pos + ahead) : '\0';
        }

        // -- Filter parsing ------------------------------------------------

        /** Filter parser that shares this query's cursor and records its end. */
        private final class FilterParser {
            private final String text;
            private int pos;

            FilterParser(String text, int start) {
                this.text = text;
                this.pos = start;
            }

            FilterExpr parseExpression() {
                FilterExpr expr = parseOr();
                skipWhitespace();
                filterEndPos = pos;
                return expr;
            }

            private FilterExpr parseOr() {
                FilterExpr left = parseAnd();
                while (true) {
                    skipWhitespace();
                    if (match("||")) {
                        left = new FilterExpr.Or(left, parseAnd());
                    } else {
                        return left;
                    }
                }
            }

            private FilterExpr parseAnd() {
                FilterExpr left = parseUnary();
                while (true) {
                    skipWhitespace();
                    if (match("&&")) {
                        left = new FilterExpr.And(left, parseUnary());
                    } else {
                        return left;
                    }
                }
            }

            private FilterExpr parseUnary() {
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == '!') {
                    // '!=' is a comparison operator, not negation + '='.
                    if (peek(1) != '=') {
                        pos++;
                        return new FilterExpr.Not(parseUnary());
                    }
                }
                return parseComparison();
            }

            private FilterExpr parseComparison() {
                FilterOperand left = parsePrimary();
                skipWhitespace();
                ComparisonOp op = parseComparisonOp();
                if (op == null) {
                    return new FilterExpr.Test(left);
                }
                FilterOperand right = parsePrimary();
                return new FilterExpr.Comparison(op, left, right);
            }

            private ComparisonOp parseComparisonOp() {
                if (pos + 1 < text.length()) {
                    String two = text.substring(pos, pos + 2);
                    ComparisonOp op = switch (two) {
                        case "==" -> ComparisonOp.EQ;
                        case "!=" -> ComparisonOp.NE;
                        case "<=" -> ComparisonOp.LE;
                        case ">=" -> ComparisonOp.GE;
                        case "=~" -> ComparisonOp.REGEX;
                        default -> null;
                    };
                    if (op != null) {
                        pos += 2;
                        return op;
                    }
                }
                if (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == '<') {
                        pos++;
                        return ComparisonOp.LT;
                    }
                    if (c == '>') {
                        pos++;
                        return ComparisonOp.GT;
                    }
                }
                return null;
            }

            private FilterOperand parsePrimary() {
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new QueryException("Unexpected end of filter expression");
                }
                char c = text.charAt(pos);
                if (c == '(') {
                    pos++;
                    FilterExpr inner = parseOr();
                    skipWhitespace();
                    if (pos >= text.length() || text.charAt(pos) != ')') {
                        throw new QueryException("Expected ')' in filter expression");
                    }
                    pos++;
                    return new FilterExpr.Group(inner);
                }
                if (c == '@' || c == '$') {
                    return parseFilterPath();
                }
                if (c == '\'' || c == '"') {
                    return new FilterExpr.Literal(new JsonString(parseQuotedString()));
                }
                if (c == '-' || Character.isDigit(c)) {
                    return new FilterExpr.Literal(parseFilterNumber());
                }
                if (Character.isLetter(c)) {
                    String word = parseWord();
                    if (word.equals("true")) {
                        return new FilterExpr.Literal(new JsonBool(true));
                    }
                    if (word.equals("false")) {
                        return new FilterExpr.Literal(new JsonBool(false));
                    }
                    if (word.equals("null")) {
                        return new FilterExpr.Literal(JsonNull.INSTANCE);
                    }
                    skipWhitespace();
                    if (pos < text.length() && text.charAt(pos) == '(') {
                        return parseFunctionCall(word);
                    }
                    throw new QueryException("Unknown name '" + word + "' in filter expression");
                }
                throw new QueryException("Unexpected character '" + c + "' in filter expression");
            }

            private FilterOperand parseFilterPath() {
                boolean absolute = text.charAt(pos) == '$';
                pos++;
                List<Segment> segments = parseFilterSegments();
                return new FilterExpr.Path(absolute, segments);
            }

            /** Segments inside a filter path; no nested filters allowed. */
            private List<Segment> parseFilterSegments() {
                List<Segment> segments = new ArrayList<>();
                while (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == '.') {
                        if (peek(1) == '.') {
                            pos += 2;
                            segments.add(new Descend(parseFilterDescendTarget()));
                        } else {
                            pos++;
                            if (pos < text.length() && text.charAt(pos) == '*') {
                                pos++;
                                segments.add(new Wildcard());
                            } else {
                                segments.add(new Name(parseBareName()));
                            }
                        }
                    } else if (c == '[') {
                        pos++;
                        skipWhitespace();
                        if (pos < text.length() && text.charAt(pos) == '?') {
                            throw new QueryException("Nested filters are not allowed");
                        }
                        segments.add(parseBracketSelectorForFilter());
                        skipWhitespace();
                        if (pos >= text.length() || text.charAt(pos) != ']') {
                            throw new QueryException("Expected ']' in filter path");
                        }
                        pos++;
                    } else {
                        break;
                    }
                }
                return segments;
            }

            private Segment parseFilterDescendTarget() {
                if (pos >= text.length()) {
                    throw new QueryException("Filter path ends after '..'");
                }
                char c = text.charAt(pos);
                if (c == '*') {
                    pos++;
                    return new Wildcard();
                }
                if (c == '[') {
                    pos++;
                    skipWhitespace();
                    Segment inner = parseBracketSelectorForFilter();
                    skipWhitespace();
                    if (pos >= text.length() || text.charAt(pos) != ']') {
                        throw new QueryException("Expected ']' in filter path");
                    }
                    pos++;
                    return inner;
                }
                return new Name(parseBareName());
            }

            private Segment parseBracketSelectorForFilter() {
                if (pos >= text.length()) {
                    throw new QueryException("Unterminated '[' in filter path");
                }
                char c = text.charAt(pos);
                if (c == '*') {
                    pos++;
                    return new Wildcard();
                }
                if (c == '\'' || c == '"') {
                    return new Name(parseQuotedString());
                }
                if (c == ':') {
                    return parseSlice(null);
                }
                if (c == '-' || Character.isDigit(c)) {
                    int start = pos;
                    if (c == '-') {
                        pos++;
                    }
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    int first = Integer.parseInt(text.substring(start, pos));
                    skipWhitespace();
                    if (pos < text.length() && text.charAt(pos) == ':') {
                        return parseSlice(first);
                    }
                    return new Index(first);
                }
                throw new QueryException("Unexpected character '" + c + "' in filter path");
            }

            private FilterOperand parseFunctionCall(String name) {
                pos++; // consume '('
                List<FilterOperand> args = new ArrayList<>();
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) != ')') {
                    while (true) {
                        args.add(parsePrimary());
                        skipWhitespace();
                        if (pos < text.length() && text.charAt(pos) == ',') {
                            pos++;
                            continue;
                        }
                        break;
                    }
                }
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ')') {
                    throw new QueryException("Expected ')' after function arguments");
                }
                pos++;
                return new FilterExpr.Function(name, args);
            }

            private JsonNumber parseFilterNumber() {
                int start = pos;
                if (text.charAt(pos) == '-') {
                    pos++;
                }
                while (pos < text.length()
                        && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.'
                                || text.charAt(pos) == 'e' || text.charAt(pos) == 'E'
                                || text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                String raw = text.substring(start, pos);
                try {
                    return new JsonNumber(Double.parseDouble(raw), raw);
                } catch (NumberFormatException e) {
                    throw new QueryException("Invalid number '" + raw + "' in filter expression");
                }
            }

            private String parseWord() {
                int start = pos;
                while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                    pos++;
                }
                return text.substring(start, pos);
            }

            private boolean match(String token) {
                if (text.startsWith(token, pos)) {
                    pos += token.length();
                    return true;
                }
                return false;
            }

            // -- Cursor helpers (own copies: the outer parser's versions
            //    operate on its own cursor and must not be used here). --

            private String parseBareName() {
                int start = pos;
                while (pos < text.length() && isNameChar(text.charAt(pos))) {
                    pos++;
                }
                if (pos == start) {
                    throw new QueryException("Expected a member name at offset " + pos);
                }
                return text.substring(start, pos);
            }

            private String parseQuotedString() {
                char quote = text.charAt(pos);
                pos++;
                StringBuilder out = new StringBuilder();
                while (true) {
                    if (pos >= text.length()) {
                        throw new QueryException("Unterminated string literal");
                    }
                    char c = text.charAt(pos++);
                    if (c == quote) {
                        return out.toString();
                    }
                    if (c == '\\') {
                        if (pos >= text.length()) {
                            throw new QueryException("Unterminated escape in string literal");
                        }
                        char esc = text.charAt(pos++);
                        switch (esc) {
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case '/' -> out.append('/');
                            case '\\' -> out.append('\\');
                            case '\'' -> out.append('\'');
                            case '"' -> out.append('"');
                            case 'u' -> {
                                if (pos + 4 > text.length()) {
                                    throw new QueryException("Truncated \\u escape");
                                }
                                int code = 0;
                                for (int i = 0; i < 4; i++) {
                                    int digit = Character.digit(text.charAt(pos++), 16);
                                    if (digit < 0) {
                                        throw new QueryException("Invalid hex digit in \\u escape");
                                    }
                                    code = (code << 4) | digit;
                                }
                                out.append((char) code);
                            }
                            default -> throw new QueryException("Invalid escape '\\" + esc + "'");
                        }
                    } else {
                        out.append(c);
                    }
                }
            }

            private Slice parseSlice(Integer first) {
                if (pos >= text.length() || text.charAt(pos) != ':') {
                    throw new QueryException("Expected ':' in slice at offset " + pos);
                }
                pos++;
                Integer second = parseOptionalInt();
                Integer third = null;
                skipWhitespace();
                if (pos < text.length() && text.charAt(pos) == ':') {
                    pos++;
                    third = parseOptionalInt();
                }
                return new Slice(first, second, third);
            }

            private Integer parseOptionalInt() {
                skipWhitespace();
                if (pos >= text.length()) {
                    return null;
                }
                char c = text.charAt(pos);
                if (c != '-' && !Character.isDigit(c)) {
                    return null;
                }
                int start = pos;
                if (c == '-') {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == start || (pos == start + 1 && text.charAt(start) == '-')) {
                    throw new QueryException("Expected a number at offset " + start);
                }
                return Integer.parseInt(text.substring(start, pos));
            }

            private void skipWhitespace() {
                while (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            private char peek(int ahead) {
                return pos + ahead < text.length() ? text.charAt(pos + ahead) : '\0';
            }
        }
    }

    // ------------------------------------------------------------------
    // Filter expressions
    // ------------------------------------------------------------------

    private enum ComparisonOp {
        EQ, NE, LT, LE, GT, GE, REGEX
    }

    /** A filter operand: a path (nodelist), a literal, or a function call. */
    private sealed interface FilterOperand permits FilterExpr.Path, FilterExpr.Literal, FilterExpr.Function, FilterExpr.Group {
        /** Evaluate to a nodelist; an empty list means Nothing (missing). */
        List<Value> evaluate(Value current, Value root);
    }

    private sealed interface FilterExpr permits FilterExpr.Or, FilterExpr.And, FilterExpr.Not,
            FilterExpr.Comparison, FilterExpr.Test {
        boolean evaluate(Value current, Value root);

        record Or(FilterExpr left, FilterExpr right) implements FilterExpr {
            @Override
            public boolean evaluate(Value current, Value root) {
                return left.evaluate(current, root) || right.evaluate(current, root);
            }
        }

        record And(FilterExpr left, FilterExpr right) implements FilterExpr {
            @Override
            public boolean evaluate(Value current, Value root) {
                return left.evaluate(current, root) && right.evaluate(current, root);
            }
        }

        record Not(FilterExpr inner) implements FilterExpr {
            @Override
            public boolean evaluate(Value current, Value root) {
                return !inner.evaluate(current, root);
            }
        }

        record Comparison(ComparisonOp op, FilterOperand left, FilterOperand right) implements FilterExpr {
            @Override
            public boolean evaluate(Value current, Value root) {
                List<Value> lefts = left.evaluate(current, root);
                List<Value> rights = right.evaluate(current, root);
                for (Value l : lefts) {
                    for (Value r : rights) {
                        if (compare(op, l, r)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }

        /** A bare operand used as a test: existence for paths, truthiness otherwise. */
        record Test(FilterOperand operand) implements FilterExpr {
            @Override
            public boolean evaluate(Value current, Value root) {
                List<Value> values = operand.evaluate(current, root);
                if (operand instanceof Path) {
                    return !values.isEmpty();
                }
                return values.size() == 1 && isTruthy(values.get(0));
            }
        }

        /** A parenthesized sub-expression used as an operand (e.g. in comparisons). */
        record Group(FilterExpr inner) implements FilterOperand {
            @Override
            public List<Value> evaluate(Value current, Value root) {
                return List.of(new JsonBool(inner.evaluate(current, root)));
            }
        }

        record Path(boolean absolute, List<Segment> segments) implements FilterOperand {
            @Override
            public List<Value> evaluate(Value current, Value root) {
                Value base = absolute ? root : current;
                List<Match> matches = List.of(new Match(base, absolute ? "$" : "@"));
                for (Segment segment : segments) {
                    List<Match> next = new ArrayList<>();
                    for (Match match : matches) {
                        next.addAll(apply(segment, match, root));
                    }
                    matches = next;
                    if (matches.isEmpty()) {
                        break;
                    }
                }
                return matches.stream().map(Match::node).toList();
            }
        }

        record Literal(Value value) implements FilterOperand {
            @Override
            public List<Value> evaluate(Value current, Value root) {
                return List.of(value);
            }
        }

        record Function(String name, List<FilterOperand> args) implements FilterOperand {
            @Override
            public List<Value> evaluate(Value current, Value root) {
                List<List<Value>> evaluated = new ArrayList<>();
                for (FilterOperand arg : args) {
                    evaluated.add(arg.evaluate(current, root));
                }
                return switch (name) {
                    case "length" -> length(evaluated);
                    case "count" -> count(evaluated);
                    case "match" -> stringTest(evaluated, true);
                    case "search" -> stringTest(evaluated, false);
                    case "value" -> value(evaluated);
                    default -> List.of(); // unknown function: Nothing, per RFC 9535
                };
            }

            private List<Value> length(List<List<Value>> args) {
                if (args.size() != 1 || args.get(0).size() != 1) {
                    return List.of();
                }
                Value value = args.get(0).get(0);
                if (value instanceof JsonString s) {
                    return List.of(new JsonNumber(s.value().codePointCount(0, s.value().length())));
                }
                if (value instanceof JsonArray a) {
                    return List.of(new JsonNumber(a.elements().size()));
                }
                if (value instanceof JsonObject o) {
                    return List.of(new JsonNumber(o.members().size()));
                }
                return List.of();
            }

            private List<Value> count(List<List<Value>> args) {
                if (args.size() != 1) {
                    return List.of();
                }
                return List.of(new JsonNumber(args.get(0).size()));
            }

            private List<Value> stringTest(List<List<Value>> args, boolean fullMatch) {
                if (args.size() != 2 || args.get(0).size() != 1 || args.get(1).size() != 1) {
                    return List.of();
                }
                if (!(args.get(0).get(0) instanceof JsonString subject)
                        || !(args.get(1).get(0) instanceof JsonString patternText)) {
                    return List.of();
                }
                Pattern pattern;
                try {
                    pattern = Pattern.compile(patternText.value());
                } catch (PatternSyntaxException e) {
                    return List.of();
                }
                boolean matched = fullMatch
                        ? pattern.matcher(subject.value()).matches()
                        : pattern.matcher(subject.value()).find();
                return List.of(new JsonBool(matched));
            }

            private List<Value> value(List<List<Value>> args) {
                if (args.size() != 1 || args.get(0).size() != 1) {
                    return List.of();
                }
                return List.of(args.get(0).get(0));
            }
        }
    }

    private static boolean isTruthy(Value value) {
        if (value instanceof JsonBool b) {
            return b.value();
        }
        if (value instanceof JsonNull) {
            return false;
        }
        if (value instanceof JsonNumber n) {
            return n.value() != 0;
        }
        if (value instanceof JsonString s) {
            return !s.value().isEmpty();
        }
        if (value instanceof JsonArray a) {
            return !a.elements().isEmpty();
        }
        if (value instanceof JsonObject o) {
            return !o.members().isEmpty();
        }
        return false;
    }

    private static boolean compare(ComparisonOp op, Value left, Value right) {
        if (op == ComparisonOp.EQ) {
            return valuesEqual(left, right);
        }
        if (op == ComparisonOp.NE) {
            return !valuesEqual(left, right);
        }
        if (op == ComparisonOp.REGEX) {
            if (left instanceof JsonString subject && right instanceof JsonString patternText) {
                try {
                    return Pattern.compile(patternText.value()).matcher(subject.value()).find();
                } catch (PatternSyntaxException e) {
                    return false;
                }
            }
            return false;
        }
        // Ordered comparisons: incomparable pairs (different types, arrays,
        // objects) are false for every operator, per RFC 9535.
        Integer ordered = compareOrdered(left, right);
        if (ordered == null) {
            return false;
        }
        return switch (op) {
            case LT -> ordered < 0;
            case LE -> ordered <= 0;
            case GT -> ordered > 0;
            case GE -> ordered >= 0;
            default -> false;
        };
    }

    /** RFC 9535 equality: numbers compare numerically, other types strictly. */
    private static boolean valuesEqual(Value left, Value right) {
        if (left instanceof JsonNumber ln && right instanceof JsonNumber rn) {
            return Double.compare(ln.value(), rn.value()) == 0;
        }
        if (left instanceof JsonString ls && right instanceof JsonString rs) {
            return ls.value().equals(rs.value());
        }
        if (left instanceof JsonBool lb && right instanceof JsonBool rb) {
            return lb.value() == rb.value();
        }
        if (left instanceof JsonNull && right instanceof JsonNull) {
            return true;
        }
        return false;
    }

    /**
     * Ordered comparison for numbers and strings; {@code null} when the pair
     * is not comparable (making {@code <}, {@code <=}, {@code >}, {@code >=}
     * all false, per RFC 9535).
     */
    private static Integer compareOrdered(Value left, Value right) {
        if (left instanceof JsonNumber ln && right instanceof JsonNumber rn) {
            return Double.compare(ln.value(), rn.value());
        }
        if (left instanceof JsonString ls && right instanceof JsonString rs) {
            return ls.value().compareTo(rs.value());
        }
        return null;
    }
}
