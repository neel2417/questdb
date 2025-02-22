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

package io.questdb.cairo.wal;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.BindVariableService;
import io.questdb.griffin.CompiledQuery;
import io.questdb.griffin.FunctionFactoryCache;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.engine.functions.bind.BindVariableServiceImpl;
import io.questdb.griffin.engine.ops.AlterOperation;
import io.questdb.griffin.engine.ops.UpdateOperation;
import io.questdb.std.Misc;
import io.questdb.std.Rnd;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

public class OperationCompiler implements Closeable {
    private final BindVariableService bindVariableService;
    private final Rnd rnd;
    private final SqlCompiler sqlCompiler;
    private final WalSqlExecutionContextImpl sqlExecutionContext;

    public OperationCompiler(
            CairoEngine engine,
            int workerCount,
            int sharedWorkerCount,
            @Nullable FunctionFactoryCache functionFactoryCache
    ) {
        rnd = new Rnd();
        bindVariableService = new BindVariableServiceImpl(engine.getConfiguration());
        sqlExecutionContext = new WalSqlExecutionContextImpl(
                engine,
                workerCount,
                sharedWorkerCount
        );
        sqlExecutionContext.with(
                AllowAllCairoSecurityContext.INSTANCE,
                bindVariableService,
                rnd,
                -1,
                null
        );
        this.sqlCompiler = new SqlCompiler(engine, functionFactoryCache, null);
    }

    @Override
    public void close() {
        Misc.free(sqlCompiler);
        Misc.free(sqlExecutionContext);
    }

    public AlterOperation compileAlterSql(CharSequence alterSql, TableToken tableToken) throws SqlException {
        sqlExecutionContext.remapTableNameResolutionTo(tableToken);
        final CompiledQuery compiledQuery = sqlCompiler.compile(alterSql, sqlExecutionContext);
        final AlterOperation alterOp = compiledQuery.getAlterOperation();
        alterOp.withContext(sqlExecutionContext);
        return alterOp;
    }

    public UpdateOperation compileUpdateSql(CharSequence updateSql, TableToken tableToken) throws SqlException {
        sqlExecutionContext.remapTableNameResolutionTo(tableToken);
        final CompiledQuery compiledQuery = sqlCompiler.compile(updateSql, sqlExecutionContext);
        final UpdateOperation updateOperation = compiledQuery.getUpdateOperation();
        updateOperation.withContext(sqlExecutionContext);
        return updateOperation;
    }

    public BindVariableService getBindVariableService() {
        return bindVariableService;
    }

    public void resetRnd(long seed0, long seed1) {
        rnd.reset(seed0, seed1);
    }

    public void setNowAndFixClock(long now) {
        sqlExecutionContext.setNowAndFixClock(now);
    }
}
