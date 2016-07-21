/**
 * Copyright 2016 The OpenZipkin Authors
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
package zipkin.dependencies;

import java.text.ParseException;
import java.util.Date;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import zipkin.internal.Util;

import static org.assertj.core.api.Assertions.assertThat;

public class ZipkinDependenciesJobTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parseDate() throws ParseException {
    long date = ZipkinDependenciesJob.parseDay("2013-05-15");
    assertThat(new Date(date))
        .hasYear(2013)
        .hasMonth(5)
        .hasDayOfMonth(15);
  }

  @Test
  public void parseDate_midnightUTC() throws ParseException {
    long date = ZipkinDependenciesJob.parseDay("2013-05-15");
    assertThat(date)
        .isEqualTo(Util.midnightUTC(date));
  }

  @Test
  public void parseDate_malformed() throws ParseException {
    thrown.expect(IllegalArgumentException.class);

    ZipkinDependenciesJob.parseDay("2013/05/15");
  }
}
