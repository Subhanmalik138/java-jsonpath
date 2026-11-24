package com.subhanmalik.jsonpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end CLI tests; success paths only (error paths call System.exit). */
class MainTest {

    private InputStream originalIn;
    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStreams() {
        originalIn = System.in;
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    private void feedStdin(String json) {
        System.setIn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String output() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsOneMatchPerLine() {
        feedStdin("{\"a\": [1, 2, 3]}");
        Main.main(new String[]{"$.a[*]"});
        assertEquals("1\n2\n3\n", output());
    }

    @Test
    void countModePrintsOnlyTheNumber() {
        feedStdin(JsonPathTest.BOOKSTORE);
        Main.main(new String[]{"--count", "$..book[?(@.price < 10)]"});
        assertEquals("2\n", output());
    }

    @Test
    void pathsModePrintsNormalizedPaths() {
        feedStdin("{\"store\": {\"book\": [{\"title\": \"A\"}]}}");
        Main.main(new String[]{"--paths", "$..title"});
        assertEquals("$['store']['book'][0]['title']\n", output());
    }

    @Test
    void rawModePrintsStringsUnquoted() {
        feedStdin("{\"name\": \"Ada\"}");
        Main.main(new String[]{"--raw", "$.name"});
        assertEquals("Ada\n", output());
    }

    @Test
    void prettyModeIndentsObjects() {
        feedStdin("{\"a\": {\"b\": 1}}");
        Main.main(new String[]{"--pretty", "$.a"});
        assertEquals("{\n  \"b\": 1\n}\n", output());
    }

    @Test
    void multipleQueriesRunInOrder() {
        feedStdin("{\"a\": 1, \"b\": 2}");
        Main.main(new String[]{"$.b", "$.a"});
        assertEquals("2\n1\n", output());
    }

    @Test
    void helpPrintsUsage() {
        feedStdin("{}");
        Main.main(new String[]{"--help"});
        assertTrue(output().startsWith("Usage: jsonpath"));
    }
}
