/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.dependencies;

import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipkinDependenciesJobTest {
  @Test void parseDate() {
    // Date assertions don't assume UTC
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    long date = ZipkinDependenciesJob.parseDay("2013-05-15");
    assertThat(new Date(date))
      .hasYear(2013)
      .hasMonth(5)
      .hasDayOfMonth(15)
      .hasHourOfDay(0)
      .hasMinute(0)
      .hasSecond(0)
      .hasMillisecond(0);
  }

  @Test void parseDate_malformed() {
    assertThatThrownBy(() -> ZipkinDependenciesJob.parseDay("2013/05/15"))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
