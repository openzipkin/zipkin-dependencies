/*
 * Copyright 2016-2023 The OpenZipkin Authors
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

package zipkin2.dependencies.banyandb;

import com.google.common.collect.ImmutableSet;
import org.apache.skywalking.banyandb.v1.client.BanyanDBClient;
import org.apache.skywalking.banyandb.v1.client.Element;
import org.apache.skywalking.banyandb.v1.client.StreamQuery;
import org.apache.skywalking.banyandb.v1.client.StreamQueryResponse;
import org.apache.skywalking.banyandb.v1.client.TimestampRange;
import org.apache.spark.Partition;
import org.apache.spark.SparkContext;
import org.apache.spark.TaskContext;
import org.apache.spark.rdd.RDD;
import scala.collection.Iterator;
import scala.collection.JavaConverters;
import scala.collection.mutable.ArrayBuffer;
import scala.reflect.ClassTag$;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class BanyanDBRDD extends RDD<BanyanDBRDD.RowData> {
  private static final Set<String> TAGS = ImmutableSet.of("trace_id", "span_id", "parent_id", "name", "duration",
      "kind", "local_endpoint_service_name", "remote_endpoint_service_name", "tags");

  private String dbAddress;
  private long startMilli;
  private long endMilli;
  private long minuteIntervalMilli;

  public BanyanDBRDD(SparkContext _sc, String dbAddress, long startMilli, long endMilli, long minuteInterval) {
    super(_sc, new ArrayBuffer<>(), ClassTag$.MODULE$.apply(RowData.class));
    this.dbAddress = dbAddress;
    this.startMilli = startMilli;
    this.endMilli = endMilli;
    this.minuteIntervalMilli = TimeUnit.MINUTES.toMillis(minuteInterval);
  }

  @Override
  public Iterator<RowData> compute(Partition partition, TaskContext taskContext) {
    final TimePartition timeRanges = (TimePartition) partition;
    final StreamQuery query = new StreamQuery("stream-zipkin_span", "zipkin_span",
        new TimestampRange(timeRanges.startMilli, timeRanges.endMilli), TAGS);
    query.setLimit(Integer.MAX_VALUE);
    final BanyanDBClient client = new BanyanDBClient(dbAddress);
    try {
      // init the client and make sure the span index is created
      client.connect();
      client.findStream("stream-zipkin_span", "zipkin_span");
      final StreamQueryResponse resp = client.query(query);
      return JavaConverters.asScalaIteratorConverter(resp.getElements().stream().map(RowData::new).iterator()).asScala();
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    } finally {
      try {
        client.close();
      } catch (IOException e) {
      }
    }
  }

  public static class RowData extends HashMap<String, Object> {
    private final String id;

    public RowData(Element element) {
      this.id = element.getId();
      this.putAll(element.getTags());
    }

    public String getTagValue(String key) {
      final Object val = this.get(key);
      return val == null ? null : Objects.toString(val);
    }

    public String getId() {
      return id;
    }
  }

  @Override
  public TimePartition[] getPartitions() {
    long currentMilli = this.startMilli;
    final ArrayList<TimePartition> result = new ArrayList<>();
    int index = 0;
    while (currentMilli <= this.endMilli) {
      result.add(new TimePartition(index++, currentMilli, currentMilli + this.minuteIntervalMilli - 1));
      currentMilli += this.minuteIntervalMilli;
    }
    return result.toArray(new TimePartition[0]);
  }

  public void setStartMilli(long startMilli) {
    this.startMilli = startMilli;
  }

  public void setEndMilli(long endMilli) {
    this.endMilli = endMilli;
  }

  public static final class TimePartition implements Partition {
    private int index;
    private long startMilli;
    private long endMilli;

    public TimePartition(int index, long startMilli, long endMilli) {
      this.index = index;
      this.startMilli = startMilli;
      this.endMilli = endMilli;
    }

    @Override
    public int index() {
      return index;
    }

    public long getStartMilli() {
      return startMilli;
    }

    public long getEndMilli() {
      return endMilli;
    }
  }
}
