package com.subhanmalik.jsonpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.subhanmalik.jsonpath.Json.JsonString;
import com.subhanmalik.jsonpath.Json.ParseException;
import com.subhanmalik.jsonpath.Json.Value;
import com.subhanmalik.jsonpath.JsonPath.Match;
import com.subhanmalik.jsonpath.JsonPath.QueryException;

/**
 * Command-line front end for the JSONPath engine.
 *
 * <pre>
 *   jsonpath [options] &lt;query&gt; [&lt;query&gt; ...]
 *
 *   -f, --file &lt;path&gt;   read JSON from a file instead of stdin
 *   --paths            print normalized paths instead of values
 *   --count            print only the number of matches per query
 *   --raw              print string values without quotes
 *   --pretty           pretty-print matched JSON values
 *   -h, --help         show usage
 * </pre>
 *
 * Each query's matches are printed one per line as compact JSON.
 * Exit code is 0 on success (even with zero matches), 1 on usage errors,
 * 2 when the input is not valid JSON, and 3 when a query is malformed.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            System.err.println("jsonpath: " + e.getMessage());
            System.err.println("Try 'jsonpath --help'.");
            System.exit(1);
        } catch (ParseException e) {
            System.err.println("jsonpath: invalid JSON: " + e.getMessage());
            System.exit(2);
        } catch (QueryException e) {
            System.err.println("jsonpath: invalid query: " + e.getMessage());
            System.exit(3);
        } catch (IOException e) {
            System.err.println("jsonpath: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        Options options = parseOptions(args);
        if (options.help) {
            printHelp();
            return;
        }
        if (options.queries.isEmpty()) {
            throw new UsageException("no query given");
        }
        if (options.paths && options.count) {
            throw new UsageException("--paths and --count cannot be combined");
        }
        if (options.raw && options.pretty) {
            throw new UsageException("--raw and --pretty cannot be combined");
        }

        String input = options.file == null
                ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(options.file, StandardCharsets.UTF_8);
        Value root = Json.parse(input);

        StringBuilder out = new StringBuilder();
        for (String queryText : options.queries) {
            List<Match> matches = JsonPath.query(root, queryText);
            if (options.count) {
                out.append(matches.size()).append('\n');
                continue;
            }
            for (Match match : matches) {
                if (options.paths) {
                    out.append(match.normalizedPath());
                } else if (options.raw && match.node() instanceof JsonString s) {
                    out.append(s.value());
                } else if (options.pretty) {
                    out.append(Json.stringifyPretty(match.node()));
                } else {
                    out.append(Json.stringify(match.node()));
                }
                out.append('\n');
            }
        }
        System.out.print(out);
    }

    private static final class Options {
        Path file;
        boolean help;
        boolean paths;
        boolean count;
        boolean raw;
        boolean pretty;
        final List<String> queries = new ArrayList<>();
    }

    private static Options parseOptions(String[] args) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> options.help = true;
                case "--paths" -> options.paths = true;
                case "--count" -> options.count = true;
                case "--raw" -> options.raw = true;
                case "--pretty" -> options.pretty = true;
                case "-f", "--file" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException(arg + " requires a file path");
                    }
                    options.file = Path.of(args[++i]);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new UsageException("unknown option '" + arg + "'");
                    }
                    options.queries.add(arg);
                }
            }
        }
        return options;
    }

    private static void printHelp() {
        System.out.print("""
                Usage: jsonpath [options] <query> [<query> ...]

                Evaluate JSONPath (RFC 9535) queries against JSON read from a
                file or stdin. Each query's matches print one per line as
                compact JSON.

                Options:
                  -f, --file <path>   read JSON from a file (default: stdin)
                  --paths             print normalized paths instead of values
                  --count             print only the number of matches
                  --raw               print string values without quotes
                  --pretty            pretty-print matched JSON values
                  -h, --help          show this help

                Examples:
                  jsonpath '$.store.book[*].title' < store.json
                  jsonpath -f store.json --count '$..book[?(@.price < 10)]'
                  jsonpath -f store.json --paths '$..author'
                """);
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
