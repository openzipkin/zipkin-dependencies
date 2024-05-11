/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.storage.elasticsearch;

import java.util.List;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;

/**
 * When OS emits a deprecation warning header in response to a method being called, the integration
 * test will fail. We cannot always fix our code however to take into account all deprecation
 * warnings, as we have to support multiple versions of ES. For these cases, add the warning message
 * to {@link #IGNORE_THESE_WARNINGS} array so it will not raise an exception anymore.
 */
abstract class IgnoredDeprecationWarnings {

  // These will be matched using header.contains(ignored[i]), so find a unique substring of the
  // warning header for it to be ignored
  static List<Pattern> IGNORE_THESE_WARNINGS = asList(
  );
}
