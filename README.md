# java-jsonpath

A zero-dependency JSONPath query engine for Java, implementing the
[RFC 9535](https://www.rfc-editor.org/rfc/rfc9535.html) expression language,
plus a small CLI (`jsonpath`) for querying JSON from files or stdin.

```bash
$ jsonpath -f examples/bookstore.json '$.store.book[*].title'
"Sayings of the Century"
"Sword of Honour"
"Moby Dick"
"The Lord of the Rings"

$ jsonpath -f examples/bookstore.json --raw '$..book[?(@.price < 10)].title'
Sayings of the Century
Moby Dick

$ jsonpath -f examples/bookstore.json --count '$..book[?(@.isbn)]'
2
```

## Why this exists

Every JSON tool in a pipeline eventually needs to answer "give me the
values at this path" — for config extraction, log analysis, API responses,
test assertions. Most Java answers pull in Jackson or a JSONPath library
with its own dialect. This project shows the whole thing can be a few
hundred lines of plain Java: a strict JSON parser, a recursive-descent
JSONPath parser, and an evaluator that follows the spec's comparison
semantics exactly.

## Features

- **Full RFC 9535 query syntax**: child segments (`.name`, `['name']`),
  indices (including negative), wildcards, slices (`[1:3]`, `[::-1]`),
  unions (`[0, 2]`, `['a', 'b']`), recursive descent (`..`),
  and filter expressions (`[?(...)]`).
- **Filter expressions** with `&&`, `||`, `!`, parentheses, comparisons
  (`== != < <= > >= =~`), existence tests, and the `length()`, `count()`,
  `match()`, `search()`, and `value()` functions. Comparisons follow the
  spec: any pair from the two operand nodelists can satisfy the test, and
  any comparison involving a missing node is false — including `!=`.
- **Normalized paths**: every match knows its canonical path, e.g.
  `$['store']['book'][0]['title']` (`--paths` prints these).
- **Strict JSON parser**: full escape and `\u` handling (including
  surrogate pairs), faithful number literals (`1.50` stays `1.50`),
  duplicate keys last-wins, and a nesting-depth cap against hostile input.
- **CLI**: multiple queries per run, `--paths`, `--count`, `--raw`,
  `--pretty`, file or stdin input, distinct exit codes for bad usage (1),
  bad JSON (2), and bad queries (3).

## Design decisions

1. **Hand-rolled everything, zero dependencies.** A JSONPath engine is
   fundamentally two small recursive-descent parsers plus a tree walk.
   Depending on a JSON library would hide the interesting part and add
   a build system; `javac` alone builds this project.
2. **Immutable value model.** JSON values are Java records behind a
   sealed interface, so the evaluator's `instanceof` chains are exhaustive
   and the compiler checks them. Numbers keep their raw literal so
   output round-trips faithfully while comparisons use the `double` value.
3. **Spec-faithful comparisons, not convenient ones.** The subtlest part
   of RFC 9535 is filter semantics: `!=` is *not* the negation of `==`
   when a node is missing, and `==` between numbers ignores literal form
   (`8.95 == 8.950`). Both are covered by tests, because this is exactly
   the kind of thing that silently breaks a pipeline.
4. **Matches carry their path.** Returning `(value, normalizedPath)`
   pairs instead of bare values makes the engine useful for reporting
   ("which field failed?") without re-walking the document.

## Usage

```bash
./build.sh            # compile, produce out/jsonpath.jar
java -jar out/jsonpath.jar --help

jsonpath [options] <query> [<query> ...]

  -f, --file <path>   read JSON from a file (default: stdin)
  --paths             print normalized paths instead of values
  --count             print only the number of matches per query
  --raw               print string values without quotes
  --pretty            pretty-print matched JSON values
  -h, --help          show help
```

More examples against `examples/bookstore.json`:

```bash
# All authors, in document order
jsonpath -f examples/bookstore.json '$..author'

# Cheap fiction, title only
jsonpath -f examples/bookstore.json --raw \
  '$.store.book[?(@.category == "fiction" && @.price < 10)].title'

# Every price in the document
jsonpath -f examples/bookstore.json '$.store..price'

# Books in reverse order
jsonpath -f examples/bookstore.json '$..book[::-1].title'

# Where does each title live?
jsonpath -f examples/bookstore.json --paths '$..book[*].title'
```

## Tests

```bash
./build.sh test
```

31 tests: JSON parsing (escapes, unicode, malformed input, depth cap),
the full RFC 9535 bookstore example (segments, slices, unions, filters,
functions, normalized paths, error cases), and CLI end-to-end behaviour.

## Tech stack

- Java 17, no external libraries (JUnit 5 console-standalone is fetched
  only to run the tests)
- `javac` + `jar` build via `build.sh` — no Maven/Gradle needed

## License

MIT — see [LICENSE](LICENSE).
