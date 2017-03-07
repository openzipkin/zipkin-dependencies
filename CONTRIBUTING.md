# Contributing to Zipkin

If you would like to contribute code you can do so through GitHub by forking the repository and sending a pull request (on a branch other than `master` or `gh-pages`).

When submitting code, please apply [Square Code Style](https://github.com/square/java-code-styles).
* If the settings import correctly, CodeStyle/Java will be named Square and use 2 space tab and indent, with 4 space continuation indent.

## License

By contributing your code, you agree to license your contribution under the terms of the APLv2: https://github.com/openzipkin/zipkin/blob/master/LICENSE

All files are released with the Apache 2.0 license.

If you are adding a new file it should have a header like below. This can be automatically added by running `./mvnw com.mycila:license-maven-plugin:format`.

```
/**
 * Copyright 2015 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
 ```

## Logging

Spark logging is managed via log4J configuration. [LogInitializer](./main/src/main/java/zipkin/dependencies/LogInitializer.java)
adds configuration during bootstrap for the "zipkin" category and
propagates it to Spark executors in a dependency free manner.

Even though Spark uses log4J underneath, declare loggers using the SLF4J
api, notable as static final field. SLF4J loggers are serializable, so
do not require special handling when part of a spark task. If you need
to test logging, encapsulate the static field in an instance method
`log()` and override it during tests.
