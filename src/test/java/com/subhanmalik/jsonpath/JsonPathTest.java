package com.subhanmalik.jsonpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.subhanmalik.jsonpath.Json.JsonNumber;
import com.subhanmalik.jsonpath.Json.JsonString;
import com.subhanmalik.jsonpath.Json.Value;
import com.subhanmalik.jsonpath.JsonPath.Match;
import com.subhanmalik.jsonpath.JsonPath.QueryException;

/**
 * Engine tests against the RFC 9535 bookstore example document.
 */
class JsonPathTest {

    static final String BOOKSTORE = """
            {
              "store": {
                "book": [
                  {"category": "reference", "author": "Nigel Rees",
                   "title": "Sayings of the Century", "price": 8.95},
                  {"category": "fiction", "author": "Evelyn Waugh",
                   "title": "Sword of Honour", "price": 12.99},
                  {"category": "fiction", "author": "Herman Melville",
                   "title": "Moby Dick", "isbn": "0-553-21311-3", "price": 8.99},
                  {"category": "fiction", "author": "J. R. R. Tolkien",
                   "title": "The Lord of the Rings", "isbn": "0-395-19395-8", "price": 22.99}
                ],
                "bicycle": {"color": "red", "price": 19.95}
              }
            }
            """;

    private final Value root = Json.parse(BOOKSTORE);

    private List<String> strings(String query) {
        return JsonPath.queryValues(root, query).stream()
                .map(v -> ((JsonString) v).value())
                .toList();
    }

    private List<Double> numbers(String query) {
        return JsonPath.queryValues(root, query).stream()
                .map(v -> ((JsonNumber) v).value())
                .toList();
    }

    @Test
    void childAndWildcardSegments() {
        assertEquals(
                List.of("Nigel Rees", "Evelyn Waugh", "Herman Melville", "J. R. R. Tolkien"),
                strings("$.store.book[*].author"));
        assertEquals(List.of("red"), strings("$.store.bicycle.color"));
        assertEquals(List.of("red"), strings("$['store']['bicycle']['color']"));
        assertEquals(2, JsonPath.queryValues(root, "$.store.*").size());
    }

    @Test
    void recursiveDescent() {
        assertEquals(4, strings("$..author").size());
        assertEquals(List.of(8.95, 12.99, 8.99, 22.99, 19.95), numbers("$.store..price"));
        assertEquals(1, JsonPath.queryValues(root, "$..book[2]").size());
        assertEquals("Moby Dick", strings("$..book[2].title").get(0));
        assertEquals("The Lord of the Rings", strings("$..book[-1].title").get(0));
    }

    @Test
    void unionAndSlice() {
        assertEquals(List.of("Sayings of the Century", "Sword of Honour"),
                strings("$..book[0, 1].title"));
        assertEquals(List.of("Sayings of the Century", "Sword of Honour"),
                strings("$..book[:2].title"));
        assertEquals(List.of("Sayings of the Century", "Moby Dick"),
                strings("$..book[::2].title"));
        assertEquals(List.of("The Lord of the Rings", "Moby Dick", "Sword of Honour", "Sayings of the Century"),
                strings("$..book[::-1].title"));
        assertEquals(List.of("Sword of Honour", "Moby Dick"),
                strings("$..book[1:3].title"));
        assertEquals(List.of(), strings("$..book[5:9].title"));
        assertEquals(List.of(), strings("$..book[1:3:0].title"));
    }

    @Test
    void filterComparisons() {
        assertEquals(List.of("Sayings of the Century", "Moby Dick"),
                strings("$..book[?(@.price < 10)].title"));
        assertEquals(List.of("The Lord of the Rings"),
                strings("$..book[?(@.price > 20)].title"));
        assertEquals(List.of("Moby Dick"),
                strings("$.store.book[?(@.category == 'fiction' && @.price < 10)].title"));
        assertEquals(List.of("Sword of Honour", "Moby Dick"),
                strings("$..book[?(@.price >= 8.99 && @.price < 22.99)].title"));
        assertEquals(List.of(8.99), numbers("$.store.book[?(@.title == 'Moby Dick')].price"));
    }

    @Test
    void filterExistenceAndNegation() {
        assertEquals(List.of("Moby Dick", "The Lord of the Rings"),
                strings("$..book[?(@.isbn)].title"));
        assertEquals(List.of("Sayings of the Century", "Sword of Honour"),
                strings("$..book[?(!@.isbn)].title"));
        // != against a missing node is false (RFC 9535), so only books
        // that actually have an isbn are compared.
        assertEquals(List.of("Moby Dick", "The Lord of the Rings"),
                strings("$..book[?(@.isbn != 'nope')].title"));
    }

    @Test
    void filterOrAndRegex() {
        assertEquals(List.of("Sayings of the Century", "Moby Dick", "The Lord of the Rings"),
                strings("$..book[?(@.price < 9 || @.price > 20)].title"));
        assertEquals(List.of("The Lord of the Rings"),
                strings("$..book[?(@.author =~ '.*Tolkien')].title"));
        assertEquals(List.of("Sword of Honour"),
                strings("$..book[?(match(@.author, '.*Waugh'))].title"));
        assertEquals(List.of("Moby Dick"),
                strings("$..book[?(search(@.title, 'Moby'))].title"));
    }

    @Test
    void filterFunctions() {
        assertEquals(List.of("Sayings of the Century", "The Lord of the Rings"),
                strings("$..book[?(length(@.title) > 15)].title"));
        // Books with exactly four members (no isbn field).
        assertEquals(List.of("Sayings of the Century", "Sword of Honour"),
                strings("$..book[?(count(@.*) == 4)].title"));
        assertEquals(List.of("Moby Dick"),
                strings("$..book[?(value(@.price) == 8.99)].title"));
    }

    @Test
    void filterWithAbsolutePathAndDescend() {
        assertEquals(4, JsonPath.queryValues(root, "$..book[?($.store.bicycle.color == 'red')]").size());
        assertEquals(List.of("Moby Dick"),
                strings("$..book[?(@..isbn == '0-553-21311-3')].title"));
    }

    @Test
    void normalizedPaths() {
        List<Match> matches = JsonPath.query(root, "$.store.book[0].title");
        assertEquals(1, matches.size());
        assertEquals("$['store']['book'][0]['title']", matches.get(0).normalizedPath());
        assertEquals(List.of("$['store']['book'][0]['title']",
                        "$['store']['book'][1]['title']",
                        "$['store']['book'][2]['title']",
                        "$['store']['book'][3]['title']"),
                JsonPath.queryPaths(root, "$..book[*].title"));
    }

    @Test
    void missingNodesYieldNoMatches() {
        assertEquals(List.of(), JsonPath.queryValues(root, "$.store.magazine"));
        assertEquals(List.of(), JsonPath.queryValues(root, "$.store.book[9]"));
        assertEquals(List.of(), JsonPath.queryValues(root, "$..book[?(@.price < 1)]"));
        assertEquals(List.of(), JsonPath.queryValues(root, "$.store.book[*].isbn[*]"));
    }

    @Test
    void rootQueryReturnsWholeDocument() {
        List<Match> matches = JsonPath.query(root, "$");
        assertEquals(1, matches.size());
        assertEquals(root, matches.get(0).node());
        assertEquals("$", matches.get(0).normalizedPath());
    }

    @Test
    void malformedQueriesAreRejected() {
        assertThrows(QueryException.class, () -> JsonPath.query(root, "store.book"));
        assertThrows(QueryException.class, () -> JsonPath.query(root, ""));
        assertThrows(QueryException.class, () -> JsonPath.query(root, "$."));
        assertThrows(QueryException.class, () -> JsonPath.query(root, "$["));
        assertThrows(QueryException.class, () -> JsonPath.query(root, "$..book[?(@.price < )]"));
        assertThrows(QueryException.class, () -> JsonPath.query(root, "$[?(@.a == @.b[?(@.c)])]"));
        assertThrows(QueryException.class, () -> JsonPath.query(root, "$.store.book[1:2:3:4]"));
    }

    @Test
    void numericEqualityIgnoresLiteralForm() {
        assertEquals(1, JsonPath.queryValues(root, "$..book[?(@.price == 8.95)]").size());
        assertEquals(1, JsonPath.queryValues(root, "$..book[?(@.price == 8.950)]").size());
    }

    @Test
    void wildcardDescendCountsEveryNode() {
        // Children of every container node: root 1 + store 2 + book array 4
        // + book members (4+4+5+5) + bicycle members 2 = 27 nodes.
        assertEquals(27, JsonPath.queryValues(root, "$..*").size());
    }
}
