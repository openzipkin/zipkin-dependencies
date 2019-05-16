/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zipkin2.dependencies;

import java.io.Serializable;
import java.util.Enumeration;
import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import zipkin2.internal.DependencyLinker;

/**
 * The explicitly initializes log configuration for zipkin categories to a predefined level. This is
 * intended to be called directly before a transformation occurs, such as {@link DependencyLinker}.
 *
 * <p><em>Motivation</em>
 *
 * <p>If you don't use this, you might find that JUL statements from zipkin don't end up in console
 * of executors (usually stderr) even if it is in console output when in local mode.
 *
 * <p>Spark workers do not have the same classpath as local mode. For example, they have a
 * different
 * log4j configuration (log4j-defaults.properties), and a classpath that doesn't load a JUL to log4J
 * bridge. This has two impacts: first, zipkin categories aren't defined, so would default to root.
 * Root logs at info level while zipkin libraries almost never log at info. Even if they did, unless
 * a bridge is installed, the log level defined in log4j wouldn't propagate to JUL. This means only
 * zipkin libraries who use log4j will end up in console output.
 *
 * <p><em>Manual fix</em>
 *
 * <p>Knowing spark uses log4j 1.2, you could remedy this by adding jul-to-slf4j and slf4j-log4j12
 * to the boot classpath of the executors (ex spark.executor.extraLibraryPath) and a custom log4j
 * configuration file (ex via spark.executor.extraJavaOptions). Both of these have deployment impact
 * including potentially conflicting with existing classpaths.
 *
 * <p><em>How this works</em>
 *
 * <p>Instead of creating and distributing static log configuration, this passes a function to
 * setup
 * logging. Since log setup can be lost as a side-effect of deserialization, this takes care to
 * idempotently apply both setup of log4j and also synchronizing JUL to the same level. The result
 * is reliable setup with no custom bootstrap needed. The tradeoff is the explicitness of the task.
 */
// This is implemented as a runnable to avoid creating and publishing a new dependency shared across
// all storage implementations. It would be nice to use some sort of lifecycle hook that would run
// after an task deserializes on an executor. Until such is available, this is the least touch way.
public final class LogInitializer implements Serializable, Runnable {
  private static final long serialVersionUID = 0L;

  /**
   * Call this prior to any phase to ensure zipkin logging is setup
   */
  static Runnable create(String zipkinLogLevel) {
    Level log4Jlevel = Level.toLevel(zipkinLogLevel);
    java.util.logging.Level julLevel = toJul(log4Jlevel);
    return new LogInitializer(log4Jlevel, julLevel);
  }

  final Level log4Jlevel;
  final java.util.logging.Level julLevel;

  LogInitializer(Level log4Jlevel, java.util.logging.Level julLevel) {
    this.log4Jlevel = log4Jlevel;
    this.julLevel = julLevel;
  }

  @Override public void run() {
    Logger zipkinLogger = LogManager.getLogger("zipkin2");
    if (!log4Jlevel.equals(zipkinLogger.getLevel())) {
      zipkinLogger.setLevel(log4Jlevel);
      if (zipkinLogger.getAdditivity()) {
        addLogAppendersFromRoot(zipkinLogger);
      }
    }
    java.util.logging.Logger.getLogger("zipkin2").setLevel(julLevel);
  }

  private static java.util.logging.Level toJul(Level log4Jlevel) {
    if (log4Jlevel.equals(Level.ALL)) return java.util.logging.Level.ALL;
    if (log4Jlevel.equals(Level.DEBUG)) return java.util.logging.Level.FINE;
    if (log4Jlevel.equals(Level.ERROR)) return java.util.logging.Level.SEVERE;
    if (log4Jlevel.equals(Level.FATAL)) return java.util.logging.Level.SEVERE;
    if (log4Jlevel.equals(Level.INFO)) return java.util.logging.Level.INFO;
    if (log4Jlevel.equals(Level.OFF)) return java.util.logging.Level.OFF;
    if (log4Jlevel.equals(Level.TRACE)) return java.util.logging.Level.FINEST;
    if (log4Jlevel.equals(Level.WARN)) return java.util.logging.Level.WARNING;
    throw new IllegalStateException("Unknown log level: " + log4Jlevel);
  }

  static void addLogAppendersFromRoot(Logger zipkinLogger) {
    zipkinLogger.setAdditivity(false);
    for (Enumeration e = Logger.getRootLogger().getAllAppenders(); e.hasMoreElements(); ) {
      zipkinLogger.addAppender((Appender) e.nextElement());
    }
  }
}
