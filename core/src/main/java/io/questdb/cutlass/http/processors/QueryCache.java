/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.http.processors;

import io.questdb.Metrics;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cutlass.http.HttpServerConfiguration;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.metrics.LongGauge;
import io.questdb.std.AssociativeCache;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public final class QueryCache implements Closeable {

    private static final Log LOG = LogFactory.getLog(QueryCache.class);
    private static ThreadLocal<QueryCache> TL_QUERY_CACHE;
    private static HttpServerConfiguration httpServerConfiguration;
    private static Metrics metrics;
    private final AssociativeCache<RecordCursorFactory> cache;

    public QueryCache(int blocks, int rows, LongGauge cachedQueriesGauge) {
        this.cache = new AssociativeCache<>(blocks, rows, cachedQueriesGauge);
    }

    public static void configure(HttpServerConfiguration configuration, Metrics metrics) {
        TL_QUERY_CACHE = new ThreadLocal<>();
        httpServerConfiguration = configuration;
        QueryCache.metrics = metrics;
    }

    public static @NotNull QueryCache getThreadLocalInstance() {
        QueryCache cache = TL_QUERY_CACHE.get();
        if (cache == null) {
            final boolean enableQueryCache = httpServerConfiguration.isQueryCacheEnabled();
            final int blockCount = enableQueryCache ? httpServerConfiguration.getQueryCacheBlockCount() : 1;
            final int rowCount = enableQueryCache ? httpServerConfiguration.getQueryCacheRowCount() : 1;
            TL_QUERY_CACHE.set(cache = new QueryCache(blockCount, rowCount, metrics.jsonQuery().cachedQueriesGauge()));
        }
        return cache;
    }

    public static QueryCache getWeakThreadLocalInstance() {
        if (TL_QUERY_CACHE != null) {
            return TL_QUERY_CACHE.get();
        }
        return null;
    }

    public void clear() {
        cache.clear();
        LOG.info().$("cleared").$();
    }

    @Override
    public void close() {
        cache.close();
        LOG.info().$("closed").$();
    }

    public RecordCursorFactory poll(CharSequence sql) {
        final RecordCursorFactory factory = cache.poll(sql);
        log(factory == null ? "miss" : "hit", sql);
        return factory;
    }

    public void push(CharSequence sql, RecordCursorFactory factory) {
        if (factory != null) {
            cache.put(sql, factory);
            log("push", sql);
        }
    }

    private void log(CharSequence action, CharSequence sql) {
        LOG.info().$(action)
                .$(" [thread=").$(Thread.currentThread().getName())
                .$(", sql=").utf8(sql)
                .I$();
    }
}
